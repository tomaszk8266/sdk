package io.github.wulkanowy.sdk.scrapper.repository

import io.github.wulkanowy.sdk.scrapper.Scrapper
import io.github.wulkanowy.sdk.scrapper.Scrapper.LoginType.ADFS
import io.github.wulkanowy.sdk.scrapper.Scrapper.LoginType.ADFSCards
import io.github.wulkanowy.sdk.scrapper.Scrapper.LoginType.ADFSLight
import io.github.wulkanowy.sdk.scrapper.Scrapper.LoginType.ADFSLightCufs
import io.github.wulkanowy.sdk.scrapper.Scrapper.LoginType.ADFSLightScoped
import io.github.wulkanowy.sdk.scrapper.Scrapper.LoginType.AUTO
import io.github.wulkanowy.sdk.scrapper.Scrapper.LoginType.STANDARD
import io.github.wulkanowy.sdk.scrapper.ScrapperException
import io.github.wulkanowy.sdk.scrapper.service.AccountService
import io.github.wulkanowy.sdk.scrapper.service.ServiceManager
import io.reactivex.Single
import java.net.URL

class AccountRepository(private val account: AccountService) {

    fun getPasswordResetCaptcha(registerBaseUrl: String, symbol: String): Single<Pair<String, String>> {
        return getPasswordResetUrl(registerBaseUrl, symbol)
            .flatMap { (_, resetUrl) ->
                account.getPasswordResetPageWithCaptcha(resetUrl)
                    .map { res -> resetUrl to res.recaptchaSiteKey }
            }
    }

    fun sendPasswordResetRequest(registerBaseUrl: String, symbol: String, email: String, captchaCode: String): Single<Pair<Boolean, String>> {
        return getPasswordResetUrl(registerBaseUrl, symbol)
            .flatMap { (type, url) ->
                when (type) {
                    STANDARD -> account.sendPasswordResetRequest(url, email, captchaCode)
                    ADFS, ADFSCards -> account.sendPasswordResetRequestADFS(url, email, captchaCode)
                    ADFSLight, ADFSLightScoped, ADFSLightCufs -> account.sendPasswordResetRequestADFSLight(url, email, captchaCode)
                    else -> throw ScrapperException("Never happen")
                }
            }
            .map {
                (it.title == "Podsumowanie operacji") to it.message
            }
    }

    private fun getPasswordResetUrl(registerBaseUrl: String, symbol: String): Single<Pair<Scrapper.LoginType, String>> {
        val url = URL(registerBaseUrl)
        return Single.just(when (url.host) {
            "fakelog.cf" -> STANDARD to "https://cufs.fakelog.cf/Default/AccountManage/UnlockAccount"
            "eszkola.opolskie.pl" -> ADFSCards to "https://konta.eszkola.opolskie.pl/maintenance/unlock.aspx"
            "edu.gdansk.pl" -> ADFS to "https://konta.edu.gdansk.pl/maintenance/unlock.aspx"
            "edu.lublin.eu" -> ADFSLightCufs to "https://logowanie.edu.lublin.eu/AccountManage/UnlockAccountRequest"
            "resman.pl" -> ADFSLight to "https://adfslight.resman.pl/AccountManage/UnlockAccountRequest"
            "umt.tarnow.pl" -> ADFS to "https://konta.umt.tarnow.pl/maintenance/unlock.aspx"
            "vulcan.net.pl" -> AUTO to "" // stream hack - bellow
            else -> throw ScrapperException("Nieznany dziennik $url")
        }).flatMap {
            if (it.first == AUTO) getLoginType(ServiceManager.UrlGenerator(url, symbol, "")).map { loginType ->
                loginType to when (loginType) {
                    STANDARD -> "https://cufs.vulcan.net.pl/Default/AccountManage/UnlockAccount"
                    ADFSLightScoped -> "https://adfslight.vulcan.net.pl/$symbol/AccountManage/UnlockAccountRequest"
                    else -> throw ScrapperException("Nieznany dziennik $registerBaseUrl, $loginType")
                }
            } else Single.just(it)
        }
    }

    private fun getLoginType(urlGenerator: ServiceManager.UrlGenerator): Single<Scrapper.LoginType> {
        return account.getFormType(urlGenerator.generate(ServiceManager.UrlGenerator.Site.LOGIN) + "Account/LogOn").map { it.page }.map {
            when {
                it.select(".LogOnBoard input[type=submit]").isNotEmpty() -> STANDARD
                it.select("form[name=form1] #SubmitButton").isNotEmpty() -> ADFS
                it.select(".submit-button, form #SubmitButton").isNotEmpty() -> {
                    it.selectFirst("form").attr("action").run {
                        when {
                            contains("cufs.edu.lublin.eu") -> ADFSLightCufs
                            startsWith("/LoginPage.aspx") -> ADFSLight
                            startsWith("/${urlGenerator.symbol}/LoginPage.aspx") -> ADFSLightScoped
                            else -> throw ScrapperException("Nieznany typ dziennika ADFS")
                        }
                    }
                }
                it.select("#PassiveSignInButton").isNotEmpty() -> ADFSCards
                else -> throw ScrapperException("Nieznany typ dziennika '${it.select("title")}")
            }
        }
    }
}
