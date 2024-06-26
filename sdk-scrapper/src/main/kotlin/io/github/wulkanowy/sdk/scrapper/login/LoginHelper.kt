package io.github.wulkanowy.sdk.scrapper.login

import io.github.wulkanowy.sdk.scrapper.CookieJarCabinet
import io.github.wulkanowy.sdk.scrapper.Scrapper
import io.github.wulkanowy.sdk.scrapper.Scrapper.LoginType.ADFS
import io.github.wulkanowy.sdk.scrapper.Scrapper.LoginType.ADFSCards
import io.github.wulkanowy.sdk.scrapper.Scrapper.LoginType.ADFSLight
import io.github.wulkanowy.sdk.scrapper.Scrapper.LoginType.ADFSLightCufs
import io.github.wulkanowy.sdk.scrapper.Scrapper.LoginType.ADFSLightScoped
import io.github.wulkanowy.sdk.scrapper.Scrapper.LoginType.AUTO
import io.github.wulkanowy.sdk.scrapper.Scrapper.LoginType.STANDARD
import io.github.wulkanowy.sdk.scrapper.exception.AccountInactiveException
import io.github.wulkanowy.sdk.scrapper.exception.ScrapperException
import io.github.wulkanowy.sdk.scrapper.exception.ServiceUnavailableException
import io.github.wulkanowy.sdk.scrapper.exception.VulcanException
import io.github.wulkanowy.sdk.scrapper.handleErrors
import io.github.wulkanowy.sdk.scrapper.isCurrentLoginHasEduOne
import io.github.wulkanowy.sdk.scrapper.register.HomePageResponse
import io.github.wulkanowy.sdk.scrapper.repository.AccountRepository.Companion.SELECTOR_ADFS
import io.github.wulkanowy.sdk.scrapper.service.LoginService
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import pl.droidsonroids.jspoon.Jspoon
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoUnit
import java.util.Locale

internal class LoginHelper(
    var loginType: Scrapper.LoginType,
    private val schema: String,
    private val host: String,
    private val domainSuffix: String,
    private val symbol: String,
    private val cookieJarCabinet: CookieJarCabinet,
    private val api: LoginService,
    private val urlGenerator: UrlGenerator,
) {

    companion object {
        @JvmStatic
        private val logger = LoggerFactory.getLogger(this::class.java)
    }

    init {
        logger.debug(toString())
    }

    private val instantFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendInstant(0)
        .toFormatter(Locale.US)

    private val certificateAdapter by lazy {
        Jspoon.create().adapter(CertificateResponse::class.java)
    }

    suspend fun login(email: String, password: String): LoginResult {
        val res = sendCredentials(email, password)
        logger.info("Login ${loginType.name} started")
        if (res.action.isBlank()) {
            throw VulcanException("Invalid certificate page: '${res.title.ifBlank { res.toString().take(32) }}'. Try again")
        }

        val homePageResponse = sendCertificate(res, email)
        logger.debug("Login completed")

        val studentModuleUrls = homePageResponse.studentSchools.map { it.attr("href") }
        val isEduOne = isCurrentLoginHasEduOne(studentModuleUrls, urlGenerator)

        logger.debug("Is school use eduOne: $isEduOne")

        checkHomepageErrors(homePageResponse)

        return LoginResult(
            isStudentSchoolUseEduOne = isEduOne,
            studentSchools = studentModuleUrls,
        )
    }

    private fun checkInvalidPasswordException(doc: Element) {
        doc.select(".ErrorMessage, #ErrorTextLabel, #loginArea #errorText").text().takeIf { it.isNotBlank() }?.let {
            val errorMessage = it.trimEnd('.')
            when {
                doc.select(SELECTOR_ADFS).isNotEmpty() -> when {
                    errorMessage.isNotBlank() -> throw BadCredentialsException(errorMessage)
                    else -> logger.warn("Unexpected login page!")
                }

                else -> throw BadCredentialsException(errorMessage)
            }
        }
    }

    private fun checkHomepageErrors(response: HomePageResponse) {
        if (response.studentSchools.isNotEmpty()) return

        response.document.select(".panel.wychowawstwo.pracownik.klient:not([style])").let {
            if ("Brak uprawnień" in it.select(".name").text()) {
                throw AccountInactiveException(it.select(".additionalText").text())
            }
        }
        response.document.select(".info-error-message-text").let {
            if ("Nie masz wystarczających uprawnień" in it.text()) {
                throw AccountInactiveException(it.text())
            }
            if ("aktualizacja bazy" in it.text()) {
                throw ServiceUnavailableException(it.text())
            }
        }
    }

    fun loginModule(site: UrlGenerator.Site): LoginModuleResult {
        val moduleUrl = urlGenerator.generate(site) + "LoginEndpoint.aspx"
        val startHtml = api.getModuleStart(moduleUrl).execute().handleErrors().body().orEmpty()
        val startDoc = Jsoup.parse(startHtml)

        if ("Working" in startDoc.title()) {
            val cert = certificateAdapter.fromHtml(startHtml)
            val certResponseHtml = api.sendCertificateModule(
                referer = urlGenerator.createReferer(site),
                url = cert.action,
                certificate = mapOf(
                    "wa" to cert.wa,
                    "wresult" to cert.wresult,
                    "wctx" to cert.wctx,
                ),
            ).execute().handleErrors().body().orEmpty()
            val certResponseDoc = Jsoup.parse(certResponseHtml)
            if ("antiForgeryToken" !in certResponseHtml) {
                throw IOException("Unknown module start page: ${certResponseDoc.title()}")
            } else {
                logger.debug("{} cookies fetch successfully!", site)
                return LoginModuleResult(
                    moduleUrl = moduleUrl.toHttpUrl(),
                    document = Jsoup.parse(certResponseHtml),
                )
            }
        } else {
            logger.debug("{} cookies already fetched!", site)
            return LoginModuleResult(
                moduleUrl = moduleUrl.toHttpUrl(),
                document = startDoc,
            )
        }
    }

    fun logout() {
        cookieJarCabinet.onRegisterUserLogout()
    }

    suspend fun sendCredentials(email: String, password: String): CertificateResponse {
        // always clear cookies to avoid problems with "request too large" errors
        cookieJarCabinet.beforeUserLogIn()
        email.substringBefore("||").let {
            return when (loginType) {
                AUTO -> throw ScrapperException("You must first specify Api.loginType before logging in")
                STANDARD -> sendStandard(it, password)
                ADFS -> {
                    when (host) {
                        "umt.tarnow.pl" -> {
                            sendADFSMS(getNormalizedADFSLogin(email, "EDUNET"), password)
                        }

                        "edu.gdansk.pl" -> {
                            sendADFSLightGeneric(email, password, ADFSLightCufs)
                        }

                        "eduportal.koszalin.pl" -> {
                            sendADFSMS(getNormalizedADFSLogin(email, "EDUPORTAL"), password)
                        }

                        "eszkola.opolskie.pl" -> {
                            sendADFSMS(getNormalizedADFSLogin(email, "EDUPORTAL"), password)
                        }

                        else -> sendADFSMS(it, password)
                    }
                }

                ADFSLight, ADFSLightScoped, ADFSLightCufs -> sendADFSLightGeneric(it, password, loginType)
                ADFSCards -> sendADFSCards(it, password)
            }
        }
    }

    suspend fun sendCertificate(cert: CertificateResponse, email: String, url: String = cert.action): HomePageResponse {
        val res = api.sendCertificate(
            referer = "$schema://cufs$domainSuffix.$host/",
            url = url,
            certificate = mapOf(
                "wa" to cert.wa,
                "wresult" to cert.wresult,
                "wctx" to cert.wctx,
            ),
        )

        if (email.contains("||")) {
            api.switchLogin("$url?rebuild=${email.substringAfter("||", "")}")
        }

        return res
    }

    @Suppress("DuplicatedCode")
    private suspend fun sendStandard(email: String, password: String): CertificateResponse {
        val symbol = urlGenerator.symbol
        val targetRealm = encode("$schema://uonetplus$domainSuffix.$host/$symbol/LoginEndpoint.aspx")
        val intermediateRealmPath = buildString {
            append("/$symbol/FS/LS")
            append("?wa=wsignin1.0")
            append("&wtrealm=$targetRealm")
            append("&wctx=${encode("auth=uonet")}")
        }
        val intermediateRealm = encode("$schema://uonetplus-logowanie$domainSuffix.$host$intermediateRealmPath")
        val returnUrl = buildString {
            append("/$symbol/FS/LS")
            append("?wa=wsignin1.0")
            append("&wtrealm=$intermediateRealm")
            append("&wctx=${encode("rm=0&id=")}")
            append("&wct=${encode(ZonedDateTime.now(ZoneId.of("UTC")).format(instantFormatter))}")
        }

        val res = certificateAdapter.fromHtml(
            api.sendCredentials(
                symbol = symbol,
                returnUrl = returnUrl,
                credentials = mapOf(
                    "LoginName" to email,
                    "Password" to password,
                ),
            ),
        )
        checkInvalidPasswordException(res.document)
        if ("uonetplus-logowanie" in res.action) {
            return certificateAdapter.fromHtml(
                api.sendCertificate(
                    url = res.action,
                    certificate = mapOf(
                        "wa" to res.wa,
                        "wresult" to res.wresult,
                        "wctx" to res.wctx,
                    ),
                ),
            )
        }
        return res
    }

    @Suppress("DuplicatedCode")
    private suspend fun sendADFSLightGeneric(email: String, password: String, type: Scrapper.LoginType): CertificateResponse {
        val res = certificateAdapter.fromHtml(
            api.sendADFSForm(
                url = getADFSUrl(type),
                values = mapOf(
                    "Username" to email,
                    "Password" to password,
                    "x" to "0",
                    "y" to "0",
                ),
            ),
        )
        checkInvalidPasswordException(res.document)

        logger.debug("Page title after credentials sent: ${res.title}, action: ${res.action} wresult: ${res.wresult.length}, wctx: ${res.wctx}")

        val res2 = certificateAdapter.fromHtml(
            api.sendADFSForm(
                url = res.action,
                values = mapOf(
                    "wa" to res.wa,
                    "wresult" to res.wresult,
                    "wctx" to res.wctx,
                ),
            ),
        )

        if ("dziennik-logowanie" in res2.action) {
            return certificateAdapter.fromHtml(
                api.sendCertificate(
                    url = res2.action,
                    certificate = mapOf(
                        "wa" to res2.wa,
                        "wresult" to res2.wresult,
                        "wctx" to res2.wctx,
                    ),
                ),
            )
        }
        return res2
    }

    private suspend fun sendADFSMS(email: String, password: String): CertificateResponse {
        val res = api.sendADFSMSForm(
            url = getADFSUrl(ADFS),
            values = mapOf(
                "UserName" to email,
                "Password" to password,
                "AuthMethod" to "FormsAuthentication",
            ),
        )

        val form = certificateAdapter.fromHtml(res)
        checkInvalidPasswordException(form.document)

        return certificateAdapter.fromHtml(
            api.sendADFSForm(
                url = form.action,
                values = mapOf(
                    "wa" to form.wa,
                    "wresult" to form.wresult,
                    "wctx" to form.wctx,
                ),
            ),
        )
    }

    private suspend fun sendADFSCards(email: String, password: String): CertificateResponse {
        val res = api.getForm(getADFSUrl(ADFSCards))

        if (res.formAction.isBlank()) throw VulcanException("Invalid ADFS login page: '${res.title}'. Try again")
        val form = certificateAdapter.fromHtml(
            api.sendADFSForm(
                url = "$schema://adfs.$host/${res.formAction.removePrefix("/")}",
                values = mapOf(
                    "__db" to res.db,
                    "__VIEWSTATE" to res.viewstate,
                    "__VIEWSTATEGENERATOR" to res.viewStateGenerator,
                    "__EVENTVALIDATION" to res.eventValidation,
                    "UsernameTextBox" to email,
                    "PasswordTextBox" to password,
                    "SubmitButton.x" to "0",
                    "SubmitButton.y" to "0",
                ),
            ),
        )
        checkInvalidPasswordException(form.document)

        return certificateAdapter.fromHtml(
            api.sendADFSForm(
                url = form.action,
                values = mapOf(
                    "wa" to form.wa,
                    "wresult" to form.wresult,
                    "wctx" to form.wctx,
                ),
            ),
        )
    }

    private fun getNormalizedADFSLogin(login: String, prefix: String): String {
        return when {
            "@" in login -> login
            "\\" in login -> login
            else -> "$prefix\\$login"
        }
    }

    private fun getADFSUrl(type: Scrapper.LoginType): String {
        val symbol = urlGenerator.symbol

        val firstStepReturnUrl = buildString {
            val realm = encode("$schema://uonetplus$domainSuffix.$host/$symbol/LoginEndpoint.aspx")
            val ctx = when (host) {
                "umt.tarnow.pl" -> encode("auth=uonet")
                else -> realm
            }
            append("/$symbol/FS/LS?wa=wsignin1.0&wtrealm=$realm&wctx=$ctx")
        }
        val id = when (type) {
            ADFS -> if (host == "eduportal.koszalin.pl") "ADFS" else "adfs"
            ADFSCards -> "eSzkola"
            ADFSLightCufs -> "AdfsLight"
            else -> "ADFS"
        }

        val query = "?wa=wsignin1.0" +
            "&wtrealm=" + encode("$schema://cufs$domainSuffix.$host${if (schema == "https") ":443" else ""}/$symbol/Account/LogOn") +
            "&wctx=" + encode("rm=0&id=$id&ru=" + encode(firstStepReturnUrl)) +
            "&wct=" + encode(DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS)))

        return when (type) {
            ADFSLight -> "$schema://adfslight.$host/LoginPage.aspx?ReturnUrl=" + encode("/$query")
            ADFSLightCufs -> "$schema://logowanie.$host/LoginPage.aspx?ReturnUrl=" + encode("/$query")
            ADFSLightScoped -> getADFSLightScopedUrl()
            else -> "$schema://adfs.$host/adfs/ls/$query"
        }
    }

    @Suppress("DuplicatedCode")
    private fun getADFSLightScopedUrl(): String {
        val symbol = urlGenerator.symbol

        val targetRealm = encode("$schema://uonetplus$domainSuffix.$host/$symbol/LoginEndpoint.aspx")
        val intermediateRealmPath = buildString {
            append("/$symbol/FS/LS")
            append("?wa=wsignin1.0")
            append("&wtrealm=$targetRealm")
            append("&wctx=${encode("auth=uonet")}")
        }
        val intermediateRealm = encode("$schema://dziennik-logowanie$domainSuffix.$host$intermediateRealmPath")
        val returnUrl = buildString {
            append("/$symbol/FS/LS")
            append("?wa=wsignin1.0")
            append("&wtrealm=$intermediateRealm")
            append("&wctx=${encode("rm=0&id=")}")
            append("&wct=${encode(ZonedDateTime.now(ZoneId.of("UTC")).format(instantFormatter))}")
        }

        val id = if (symbol == "rzeszowprojekt") "AdfsLight" else "ADFSLight"
        val query = buildString {
            append("?wa=wsignin1.0")
            append("&wtrealm=${encode("https://cufs$domainSuffix.$host/$symbol/Account/LogOn")}")
            append("&wctx=${encode("rm=0&id=$id&ru=" + encode(returnUrl))}")
            append("&wct=${encode(ZonedDateTime.now(ZoneId.of("UTC")).format(instantFormatter))}")
        }

        return "$schema://adfslight.$host/$symbol/LoginPage.aspx?ReturnUrl=" + encode("/$symbol/Default.aspx$query")
    }

    private fun encode(url: String) = URLEncoder.encode(url, "UTF-8")
}
