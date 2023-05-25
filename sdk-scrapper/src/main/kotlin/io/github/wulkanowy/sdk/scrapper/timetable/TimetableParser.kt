package io.github.wulkanowy.sdk.scrapper.timetable

import io.github.wulkanowy.sdk.scrapper.capitalise
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

internal class TimetableParser {

    private companion object {
        const val CLASS_PLANNED = "x-treelabel-ppl"
        const val CLASS_REALIZED = "x-treelabel-rlz"
        const val CLASS_CHANGES = "x-treelabel-zas"
        const val CLASS_MOVED_OR_CANCELED = "x-treelabel-inv"

        const val INFO_REPLACEMENT_TEACHER = "(zastępstwo"
        const val INFO_REPLACEMENT_ROOM = "(zmieniono salę"
    }

    fun getTimetable(c: TimetableCell): Lesson? {
        return addLessonDetails(Lesson(c.number, c.start, c.end, c.date), c.td)
    }

    private fun addLessonDetails(lesson: Lesson, td: Element): Lesson? {
        val divs = td.select("div:not([class])")

        return when (divs.size) {
            1 -> getLessonInfo(lesson, divs[0])
            2 -> getLessonInfoForDuoDivs(lesson, divs)
            3 -> getLessonInfoForTripleDivs(lesson, divs)
            else -> null
        }?.let {
            td.select(".uwaga-panel").getOrNull(0)?.let { warn ->
                if (it.info.isBlank()) it.copy(info = warn.text())
                else it.copy(info = "${it.info}: ${warn.text()}")
            } ?: it
        }
    }

    private fun getLessonInfoForDuoDivs(lesson: Lesson, divs: Elements) = when {
        divs.has(1, CLASS_MOVED_OR_CANCELED) -> {
            when {
                divs[1]?.selectFirst("span")?.hasClass(CLASS_PLANNED) == true -> getLessonInfo(lesson, divs[0]).run {
                    val old = getLessonInfo(lesson, divs[1])
                    copy(
                        changes = true,
                        subjectOld = old.subject,
                        teacherOld = old.teacher,
                        roomOld = old.room,
                        info = stripLessonInfo("${getFormattedLessonInfo(info)}, ${old.info}").replace("$subject ", "").capitalise(),
                    )
                }
                else -> getLessonInfo(lesson, divs[1])
            }
        }
        divs.has(1, CLASS_CHANGES) -> getLessonInfo(lesson, divs[1]).run {
            val old = getLessonInfo(lesson, divs[0])
            copy(
                changes = true,
                canceled = false,
                subjectOld = old.subject,
                teacherOld = old.teacher,
                roomOld = old.room,
            )
        }
        divs.has(0, CLASS_MOVED_OR_CANCELED) && divs.has(0, CLASS_PLANNED) && divs.has(1, null) -> {
            getLessonInfo(lesson, divs[1]).run {
                val old = getLessonInfo(lesson, divs[0])
                copy(
                    changes = true,
                    canceled = false,
                    subjectOld = old.subject,
                    teacherOld = old.teacher,
                    roomOld = old.room,
                    info = getFormattedLessonInfo(info).ifEmpty { "Poprzednio: ${old.subject} (${old.info})" },
                )
            }
        }
        divs.has(0, CLASS_CHANGES) -> {
            val oldLesson = getLessonInfo(lesson, divs[0])
            val newLesson = getLessonInfo(lesson, divs[1])
            val isNewLessonEmpty = divs[1]?.select("span").isNullOrEmpty()
            if (!isNewLessonEmpty && oldLesson.teacher == newLesson.teacher) {
                newLesson.copy(
                    subjectOld = oldLesson.subject,
                    roomOld = oldLesson.room,
                    teacherOld = oldLesson.teacherOld,
                    changes = true,
                )
            } else oldLesson
        }
        else -> getLessonInfo(lesson, divs[0])
    }

    private fun getLessonInfoForTripleDivs(lesson: Lesson, divs: Elements) = when {
        divs.has(0, CLASS_CHANGES) && divs.has(1, CLASS_MOVED_OR_CANCELED) && divs.has(2, CLASS_MOVED_OR_CANCELED) -> {
            getLessonInfo(lesson, divs[0]).run {
                val old = getLessonInfo(lesson, divs[1])
                copy(
                    changes = true,
                    canceled = false,
                    subjectOld = old.subject,
                    teacherOld = old.teacher,
                    roomOld = old.room,
                )
            }
        }
        divs.has(0, CLASS_MOVED_OR_CANCELED) && divs.has(1, CLASS_MOVED_OR_CANCELED) && divs.has(2, CLASS_CHANGES) -> {
            getLessonInfo(lesson, divs[2]).run {
                val old = getLessonInfo(lesson, divs[0])
                copy(
                    changes = true,
                    canceled = false,
                    subjectOld = old.subject,
                    teacherOld = old.teacher,
                    roomOld = old.room,
                )
            }
        }
        divs.has(0, CLASS_MOVED_OR_CANCELED) && divs.has(1, CLASS_CHANGES) && divs.has(1, CLASS_MOVED_OR_CANCELED) && divs.has(2, null) -> {
            val oldLesson = getLessonInfo(lesson, divs[0])
            getLessonInfo(lesson, divs[2]).copy(
                subjectOld = oldLesson.subject,
                teacherOld = oldLesson.teacher,
                roomOld = oldLesson.room,
            )
        }
        else -> getLessonInfo(lesson, divs[1])
    }

    private fun Elements.has(index: Int, className: String?): Boolean {
        return this[index]?.selectFirst("span").let {
            when (className) {
                null -> it?.attr("class").isNullOrBlank()
                else -> it?.hasClass(className) == true
            }
        }
    }

    private fun getLessonInfo(lesson: Lesson, div: Element) = div.select("span").run {
        when {
            size == 2 -> getLessonLight(lesson, this, div.ownText())
            size == 3 && div.ownText().contains(INFO_REPLACEMENT_TEACHER, true) -> getSimpleLessonWithNewReplacementTeacher(
                lesson = lesson,
                spans = this,
                offset = 0,
                changes = div.ownText(),
            )
            size == 3 && div.ownText().contains(INFO_REPLACEMENT_ROOM, true) -> getSimpleLessonWithNewReplacementRoom(
                lesson = lesson,
                spans = this,
                offset = 0,
                changes = div.ownText(),
            )
            size == 3 -> getSimpleLesson(lesson, this, changes = div.ownText())
            size == 4 && div.ownText().contains(INFO_REPLACEMENT_TEACHER, true) -> getSimpleLessonWithNewReplacementTeacher(
                lesson = lesson,
                spans = this,
                offset = 1,
                changes = div.ownText(),
            )
            size == 4 && div.ownText().contains(INFO_REPLACEMENT_ROOM, true) -> getSimpleLessonWithNewReplacementRoom(
                lesson = lesson,
                spans = this,
                offset = 1,
                changes = div.ownText(),
            )
            size == 4 && last()?.hasClass(CLASS_REALIZED) == true -> getSimpleLesson(lesson, this, changes = div.ownText())
            size == 4 -> getGroupLesson(lesson, this, changes = div.ownText())
            size == 5 && first()?.hasClass(CLASS_CHANGES) == true && select(".$CLASS_REALIZED").size == 2 -> getSimpleLesson(
                lesson = lesson,
                spans = this,
                infoExtraOffset = 1,
                changes = div.ownText(),
            )
            size == 5 && last()?.hasClass(CLASS_REALIZED) == true -> getGroupLesson(
                lesson = lesson,
                spans = this,
                changes = div.ownText(),
            )
            size == 7 -> getSimpleLessonWithReplacement(lesson, spans = this)
            size == 9 -> getGroupLessonWithReplacement(lesson, spans = this)
            else -> lesson
        }
    }

    private fun getSimpleLesson(lesson: Lesson, spans: Elements, infoExtraOffset: Int = 0, changes: String): Lesson {
        return getLesson(lesson, spans, 0, infoExtraOffset, changes)
    }

    private fun getSimpleLessonWithNewReplacementRoom(lesson: Lesson, spans: Elements, offset: Int, changes: String): Lesson {
        return getLessonWithReplacementRoom(lesson, spans, offset, changes = changes)
    }

    private fun getSimpleLessonWithNewReplacementTeacher(lesson: Lesson, spans: Elements, offset: Int, changes: String): Lesson {
        return getLessonWithReplacementTeacher(lesson, spans, offset, changes = changes)
    }

    private fun getSimpleLessonWithReplacement(lesson: Lesson, spans: Elements): Lesson {
        return getLessonWithReplacement(lesson, spans)
    }

    private fun getGroupLesson(lesson: Lesson, spans: Elements, changes: String): Lesson {
        return getLesson(lesson, spans, offset = 1, changes = changes)
    }

    private fun getGroupLessonWithReplacement(lesson: Lesson, spans: Elements): Lesson {
        return getLessonWithReplacement(lesson, spans, 1)
    }

    private fun getLessonLight(lesson: Lesson, spans: Elements, info: String): Lesson {
        val firstElementClasses = spans.first()?.classNames().orEmpty()
        val isCanceled = CLASS_MOVED_OR_CANCELED in firstElementClasses
        val isChanged = CLASS_CHANGES in firstElementClasses
        return lesson.copy(
            subject = getLessonAndGroupInfoFromSpan(spans[0])[0],
            group = getLessonAndGroupInfoFromSpan(spans[0])[1],
            room = spans[1].text(),
            info = getFormattedLessonInfo(info),
            canceled = isCanceled,
            changes = (info.isNotBlank() && !isCanceled) || isChanged,
        )
    }

    private fun getLesson(lesson: Lesson, spans: Elements, offset: Int = 0, infoExtraOffset: Int = 0, changes: String): Lesson {
        val firstElementClasses = spans.first()?.classNames().orEmpty()
        val isCanceled = CLASS_MOVED_OR_CANCELED in firstElementClasses
        val isChanged = CLASS_CHANGES in firstElementClasses
        return lesson.copy(
            subject = getLessonAndGroupInfoFromSpan(spans[0])[0],
            group = getLessonAndGroupInfoFromSpan(spans[0])[1],
            room = spans[1 + offset].text(),
            teacher = spans[2 + offset].text(),
            info = getFormattedLessonInfo(spans.getOrNull(3 + offset + infoExtraOffset)?.text() ?: changes),
            canceled = isCanceled,
            changes = (changes.isNotBlank() && !isCanceled) || isChanged,
        )
    }

    private fun getLessonWithReplacementRoom(lesson: Lesson, spans: Elements, offset: Int, changes: String): Lesson {
        return lesson.copy(
            subject = getLessonAndGroupInfoFromSpan(spans[0])[0],
            group = getLessonAndGroupInfoFromSpan(spans[0])[1],
            room = spans[1 + offset].text(),
            roomOld = getRoomFromInfo(changes),
            teacher = spans[2 + offset].text(),
            info = getRoomChangesWithoutSubstitution(changes),
            changes = true,
        )
    }

    private fun getLessonWithReplacementTeacher(lesson: Lesson, spans: Elements, offset: Int, changes: String): Lesson {
        return lesson.copy(
            subject = getLessonAndGroupInfoFromSpan(spans[0])[0],
            group = getLessonAndGroupInfoFromSpan(spans[0])[1],
            room = spans[1 + offset].text(),
            teacher = getTeacherFromInfo(changes),
            teacherOld = spans[2 + offset].text(),
            info = getTeacherChangesWithoutSubstitution(changes),
            changes = true,
        )
    }

    private fun getLessonWithReplacement(lesson: Lesson, spans: Elements, offset: Int = 0) = lesson.copy(
        subject = getLessonAndGroupInfoFromSpan(spans[3 + offset])[0],
        subjectOld = getLessonAndGroupInfoFromSpan(spans[0])[0],
        group = getLessonAndGroupInfoFromSpan(spans[3 + offset])[1],
        teacher = spans[4 + offset * 2].text(),
        teacherOld = spans[1 + offset].text(),
        room = spans[5 + offset * 2].text(),
        roomOld = spans[2 + offset].text(),
        info = "${getFormattedLessonInfo(spans.last()?.text())}, poprzednio: ${getLessonAndGroupInfoFromSpan(spans[0])[0]}",
        changes = true,
    )

    private fun getFormattedLessonInfo(info: String?) = info?.trim()?.removeSurrounding("(", ")").orEmpty()

    private fun getTeacherFromInfo(info: String?) = info?.substringAfter("(zastępstwo: ")?.substringBefore(")").orEmpty()

    private fun getRoomFromInfo(info: String?) = info?.substringAfter("(zmieniono salę z ")?.substringBefore(" na").orEmpty()

    private fun getTeacherChangesWithoutSubstitution(changes: String?) = changes?.substringBefore("(zastępstwo: ").orEmpty()
    private fun getRoomChangesWithoutSubstitution(changes: String?) = changes?.substringBefore("(zmieniono salę z ").orEmpty()

    private fun stripLessonInfo(info: String) = info
        .replace("okienko dla uczniów", "")
        .replace("zmiana organizacji zajęć", "")
        .replace(" ,", "")
        .removePrefix(", ")

    private fun getLessonAndGroupInfoFromSpan(span: Element) = arrayOf(
        span.text().substringBefore(" ["),
        if (span.text().contains("[")) span.text().split(" [").last().removeSuffix("]") else "",
    )
}
