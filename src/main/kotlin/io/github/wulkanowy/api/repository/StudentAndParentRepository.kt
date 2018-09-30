package io.github.wulkanowy.api.repository

import io.github.wulkanowy.api.attendance.Attendance
import io.github.wulkanowy.api.attendance.AttendanceSummary
import io.github.wulkanowy.api.exams.Exam
import io.github.wulkanowy.api.grades.Grade
import io.github.wulkanowy.api.grades.GradeStatistics
import io.github.wulkanowy.api.grades.GradeSummary
import io.github.wulkanowy.api.homework.Homework
import io.github.wulkanowy.api.mobile.Device
import io.github.wulkanowy.api.mobile.TokenResponse
import io.github.wulkanowy.api.notes.Note
import io.github.wulkanowy.api.realized.Realized
import io.github.wulkanowy.api.register.StudentAndParentResponse
import io.github.wulkanowy.api.school.Teacher
import io.github.wulkanowy.api.service.StudentAndParentService
import io.github.wulkanowy.api.student.StudentInfo
import io.github.wulkanowy.api.timetable.Timetable
import io.github.wulkanowy.api.timetable.TimetableParser
import io.reactivex.Single
import org.threeten.bp.DayOfWeek
import org.threeten.bp.Instant
import org.threeten.bp.LocalDate
import org.threeten.bp.ZoneId
import org.threeten.bp.format.DateTimeFormatter
import org.threeten.bp.temporal.TemporalAdjusters
import java.text.SimpleDateFormat
import java.util.*

class StudentAndParentRepository(private val api: StudentAndParentService) {

    fun getSchoolInfo(): Single<StudentAndParentResponse> {
        return api.getSchoolInfo()
    }

    fun getAttendance(startDate: LocalDate, endDate: LocalDate? = null): Single<List<Attendance>> {
        val end = endDate ?: startDate.plusDays(4)
        return api.getAttendance(startDate.getLastMonday().toTick()).map { res ->
            res.rows.flatMap { row ->
                row.lessons.mapIndexedNotNull { i, it ->
                    if ("null" == it.subject) return@mapIndexedNotNull null // fix empty days
                    it.apply {
                        date = res.days[i]
                        number = row.number
                        presence = it.type == Attendance.Types.PRESENCE || it.type == Attendance.Types.ABSENCE_FOR_SCHOOL_REASONS
                        absence = it.type == Attendance.Types.ABSENCE_UNEXCUSED || it.type == Attendance.Types.ABSENCE_EXCUSED
                        lateness = it.type == Attendance.Types.EXCUSED_LATENESS || it.type == Attendance.Types.UNEXCUSED_LATENESS
                        excused = it.type == Attendance.Types.ABSENCE_EXCUSED || it.type == Attendance.Types.EXCUSED_LATENESS
                        exemption = it.type == Attendance.Types.EXEMPTION
                    }
                }
            }.asSequence().filter {
                it.date.toLocalDate() >= startDate && it.date.toLocalDate() <= end
            }.sortedWith(compareBy({ it.date }, { it.number })).toList()
        }
    }

    fun getAttendanceSummary(subjectId: Int?): Single<List<AttendanceSummary>> {
        return api.getAttendanceSummary(subjectId).map { res ->
            res.days.mapIndexed { i, day ->
                AttendanceSummary(day,
                        res.rows[0].value[i].toIntOrNull() ?: 0,
                        res.rows[1].value[i].toIntOrNull() ?: 0,
                        res.rows[2].value[i].toIntOrNull() ?: 0,
                        res.rows[3].value[i].toIntOrNull() ?: 0,
                        res.rows[4].value[i].toIntOrNull() ?: 0,
                        res.rows[5].value[i].toIntOrNull() ?: 0,
                        res.rows[6].value[i].toIntOrNull() ?: 0
                )
            }
        }
    }

    fun getExams(startDate: LocalDate, endDate: LocalDate? = null): Single<List<Exam>> {
        val end = endDate ?: startDate.plusDays(4)
        return api.getExams(startDate.getLastMonday().toTick()).map { res ->
            res.days.flatMap { day ->
                day.exams.map { exam ->
                    exam.date = day.date
                    if (exam.group.contains(" ")) exam.group = ""
                    exam
                }
            }.asSequence().filter {
                it.date.toLocalDate() >= startDate && it.date.toLocalDate() <= end
            }.sortedBy { it.date }.toList()
        }
    }

    fun getGrades(semesterId: Int?): Single<List<Grade>> {
        return api.getGrades(semesterId).map { res ->
            res.grades.asSequence().map { grade ->
                if (grade.entry == grade.comment) grade.comment = ""
                if (grade.description == grade.symbol) grade.description = ""
                grade
            }.sortedWith(compareBy({ it.date }, { it.subject })).toList()
        }
    }

    fun getGradesSummary(semesterId: Int?): Single<List<GradeSummary>> {
        return api.getGradesSummary(semesterId).map { res ->
            res.subjects.asSequence().map { summary ->
                summary.predicted = getGradeShortValue(summary.predicted)
                summary.final = getGradeShortValue(summary.final)
                summary
            }.sortedBy { it.name }.toList()
        }
    }

    fun getGradesStatistics(semesterId: Int?, annual: Boolean): Single<List<GradeStatistics>> {
        return api.getGradesStatistics(if (!annual) 1 else 2, semesterId).map { res ->
            res.items.map {
                it.apply {
                    this.gradeValue = getGradeShortValue(this.grade).toIntOrNull() ?: 0
                    this.semesterId = res.semesterId
                }
            }
        }
    }

    fun getHomework(startDate: LocalDate, endDate: LocalDate? = null): Single<List<Homework>> {
        val end = endDate ?: startDate.plusDays(4)
        return api.getHomework(startDate.getLastMonday().toTick()).map { res ->
            res.items.asSequence().map { item ->
                item.date = res.date
                item
            }.filter {
                it.date.toLocalDate() >= startDate && it.date.toLocalDate() <= end
            }.sortedWith(compareBy({ it.date }, { it.subject })).toList()
        }
    }

    fun getNotes(): Single<List<Note>> {
        return api.getNotes().map { res ->
            res.notes.asSequence().mapIndexed { i, note ->
                note.date = res.dates[i]
                note
            }.sortedWith(compareBy({ it.date }, { it.category })).toList()
        }
    }

    fun getRegisteredDevices(): Single<List<Device>> {
        return api.getRegisteredDevices().map { it.devices }
    }

    fun getToken(): Single<TokenResponse> {
        return api.getToken()
    }

    fun unregisterDevice(id: Int): Single<List<Device>> {
        return api.unregisterDevice(id).map { it.devices }
    }

    fun getTeachers(): Single<List<Teacher>> {
        return api.getSchoolAndTeachers().map { res ->
            res.subjects.flatMap { subject ->
                subject.teachers.split(", ").map { teacher ->
                    teacher.split(" [").run {
                        Teacher(first(), last().removeSuffix("]"), subject.name)
                    }
                }
            }.sortedWith(compareBy({ it.subject }, { it.name }))
        }
    }

    fun getStudentInfo(): Single<StudentInfo> {
        return api.getStudentInfo().map {
            it.student.polishCitizenship = if ("Tak" == it.student.polishCitizenship) "1" else "0"
            it
        }
    }

    fun getTimetable(startDate: LocalDate, endDate: LocalDate? = null): Single<List<Timetable>> {
        val end = endDate ?: startDate.plusDays(4)
        return api.getTimetable(startDate.getLastMonday().toTick()).map { res ->
            res.rows.flatMap { row ->
                row.lessons.asSequence().mapIndexed { i, it ->
                    it.date = res.days[i]
                    it.start = "${it.date.toLocalDate().toFormat("yyy-MM-dd")} ${row.startTime}".toDate("yyyy-MM-dd HH:mm")
                    it.end = "${it.date.toLocalDate().toFormat("yyy-MM-dd")} ${row.endTime}".toDate("yyyy-MM-dd HH:mm")
                    it.number = row.number
                    it
                }.mapNotNull { TimetableParser().getTimetable(it) }.toList()
            }.asSequence().filter {
                it.date.toLocalDate() >= startDate && it.date.toLocalDate() <= end
            }.sortedWith(compareBy({ it.date }, { it.number })).toList()
        }
    }

    fun getRealized(startDate: LocalDate?): Single<List<Realized>> {
        return api.getRealized(startDate.toTick(), null, null).map { res ->
            lateinit var lastDate: Date
            res.items.asSequence().mapNotNull {
                if (it.subject.isBlank()) {
                    lastDate = it.date
                    return@mapNotNull null
                }

                it.apply { date = lastDate }
            }.sortedWith(compareBy({ it.date }, { it.number })).toList()
        }
    }

    private fun getGradeShortValue(value: String): String {
        return when (value) {
            "celujący" -> "6"
            "bardzo dobry" -> "5"
            "dobry" -> "4"
            "dostateczny" -> "3"
            "dopuszczający" -> "2"
            "niedostateczny" -> "1"
            else -> value
        }
    }

    private fun String.toDate(format: String) = SimpleDateFormat(format).parse(this)

    private fun Date.toLocalDate() = Instant.ofEpochMilli(this.time).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun LocalDate.toDate() = java.sql.Date.valueOf(this.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))

    private fun LocalDate.toFormat(format: String) = this.format(DateTimeFormatter.ofPattern(format))

    private fun LocalDate.getLastMonday() = this.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun LocalDate?.toTick() = this?.toDate().toTick()

    private fun Date?.toTick(): String {
        if (this == null) return ""
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeZone = TimeZone.getDefault()
            time = this@toTick
        }
        val utcOffset = c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)
        return ((c.timeInMillis + utcOffset) * 10000 + 621355968000000000L).toString()
    }
}
