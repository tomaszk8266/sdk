package io.github.wulkanowy.sdk.scrapper

import io.github.wulkanowy.sdk.scrapper.messages.Mailbox
import io.github.wulkanowy.sdk.scrapper.messages.Recipient
import io.github.wulkanowy.sdk.scrapper.messages.RecipientType
import org.jsoup.Jsoup
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.time.Instant.ofEpochMilli
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId.systemDefault
import java.time.format.DateTimeFormatter.ofPattern
import java.util.Date
import kotlin.math.roundToInt

internal fun String.toDate(format: String): Date = SimpleDateFormat(format).parse(this)

internal fun String.toLocalDate(format: String): LocalDate = LocalDate.parse(this, ofPattern(format))

internal fun Date.toLocalDate(): LocalDate = ofEpochMilli(time).atZone(systemDefault()).toLocalDate()

internal fun LocalDate.toFormat(format: String): String = format(ofPattern(format))

internal fun LocalDateTime.toFormat(format: String): String = format(ofPattern(format))

internal fun LocalDate.getSchoolYear(): Int = if (month.value > 8) year else year - 1

internal fun getGradeShortValue(value: String?): String {
    return when (value?.trim()) {
        "celujący" -> "6"
        "bardzo dobry" -> "5"
        "dobry" -> "4"
        "dostateczny" -> "3"
        "dopuszczający" -> "2"
        "niedostateczny" -> "1"
        else -> value.orEmpty().trim()
    }
}

internal fun String.getEmptyIfDash(): String {
    return when {
        this == "-" -> ""
        else -> this
    }
}

internal fun String.getGradePointPercent(): String {
    return split("/").let { (student, max) ->
        if (max.toDouble() == 0.0) return this
        "${(student.toDouble() / max.toDouble() * 100).roundToInt()}%"
    }
}

internal fun getScriptParam(name: String, content: String, fallback: String = ""): String {
    return "$name: '(.)*'".toRegex().find(content).let { result ->
        if (null !== result) Jsoup.parse(result.groupValues[0].substringAfter("'").substringBefore("'")).text() else fallback
    }
}

internal fun getScriptFlag(name: String, content: String, fallback: Boolean = false): Boolean {
    return "$name: (false|true)".toRegex().find(content).let { result ->
        if (null !== result) result.groupValues[1].toBoolean() else fallback
    }
}

fun String.getNormalizedSymbol(): String = this
    .trim().lowercase()
    .replace("default", "")
    .run {
        Normalizer.normalize(this, Normalizer.Form.NFD).run {
            "\\p{InCombiningDiacriticalMarks}+".toRegex().replace(this, "")
        }
    }
    .replace("ł", "l")
    .replace("[^a-z0-9]".toRegex(), "")
    .ifBlank { "Default" }

internal fun List<Recipient>.normalizeRecipients() = map { it.parseName() }

internal fun Recipient.parseName(): Recipient {
    val typeSeparatorPosition = fullName.indexOfAny(RecipientType.entries.map { " - ${it.letter} - " })

    if (typeSeparatorPosition == -1) return copy(userName = fullName)

    val userName = fullName.substring(0..typeSeparatorPosition).trim()
    val typeLetter = fullName.substring(typeSeparatorPosition..typeSeparatorPosition + 3 * 2 + 1).substringAfter(" - ").substringBefore(" - ")
    val studentName = fullName.substringAfter(" - $typeLetter - ").substringBefore(" - (")
    val schoolName = fullName.substringAfter("(").trimEnd(')')
    return copy(
        userName = userName,
        studentName = studentName.takeIf { it != "($schoolName)" } ?: userName,
        type = typeLetter.let { letter -> RecipientType.entries.first { it.letter == letter } },
        schoolNameShort = schoolName,
    )
}

internal fun Mailbox.toRecipient() = Recipient(
    mailboxGlobalKey = globalKey,
    type = type,
    fullName = fullName,
    userName = userName,
    studentName = studentName,
    schoolNameShort = schoolNameShort,
)

internal fun Recipient.toMailbox() = Mailbox(
    globalKey = mailboxGlobalKey,
    userType = -1,
    type = type,
    fullName = fullName,
    userName = userName,
    studentName = studentName,
    schoolNameShort = schoolNameShort,
)

internal fun String.capitalise() = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

internal val defaultUserAgentTemplate = buildString {
    append("Mozilla/5.0 (Linux; Android %1\$s; %2\$s) ")
    append("AppleWebKit/%3\$s (KHTML, like Gecko) ")
    append("Chrome/%4\$s Mobile ")
    append("Safari/%5\$s")
}

internal fun getFormattedString(
    template: String,
    androidVersion: String,
    buildTag: String,
    webKitRev: String = "537.36",
    chromeRev: String = "120.0.0.0",
): String {
    return String.format(template, androidVersion, buildTag, webKitRev, chromeRev, webKitRev)
}
