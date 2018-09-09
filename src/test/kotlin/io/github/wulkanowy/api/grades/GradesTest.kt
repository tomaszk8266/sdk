package io.github.wulkanowy.api.grades

import io.github.wulkanowy.api.BaseTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GradesTest : BaseTest() {

    private val grades by lazy {
        getSnpRepo(GradesTest::class.java, "OcenyWszystkie-filled.html").getGrades(0).blockingGet()
    }

    @Test fun getAllTest() {
        assertEquals(7, grades.size) // 2 items are skipped
    }

    @Test fun getSubjectTest() {
        assertEquals("Zajęcia z wychowawcą", grades[1].subject)
        assertEquals("Wychowanie fizyczne", grades[3].subject)
        assertEquals("Język polski", grades[4].subject)
        assertEquals("Język angielski", grades[5].subject)
    }

    @Test fun getValueTest() {
        assertEquals("1", grades[0].value)
        assertEquals("4-", grades[2].value)
        assertEquals("5", grades[4].value)
        assertEquals("5", grades[5].value)
    }

    @Test fun getColorTest() {
        assertEquals("6ECD07", grades[0].color)
        assertEquals("000000", grades[1].color)
        assertEquals("F04C4C", grades[2].color)
        assertEquals("1289F7", grades[5].color)
    }

    @Test fun getSymbolTest() {
        assertEquals("K", grades[0].symbol)
        assertEquals("S1", grades[2].symbol)
        assertEquals("STR", grades[3].symbol)
        assertEquals("+Odp", grades[4].symbol)
    }

    @Test fun getDescriptionTest() {
        assertEquals("Dzień Kobiet w naszej klasie", grades[1].description)
        assertEquals("PIERWSZA POMOC I RESUSCYTACJA", grades[2].description)
        assertEquals("", grades[3].description)
        assertEquals("Kordian", grades[4].description)
        assertEquals("Writing", grades[5].description)
    }

    @Test fun getWeightTest() {
        assertEquals("5,00", grades[2].weight)
        assertEquals("8,00", grades[3].weight)
        assertEquals("5,00", grades[4].weight)
        assertEquals("3,00", grades[5].weight)
    }

    @Test fun getDateTest() {
        assertEquals(getDate(2017, 2, 6), grades[0].date)
        assertEquals(getDate(2017, 4, 2), grades[3].date)
        assertEquals(getDate(2017, 5, 11), grades[4].date)
        assertEquals(getDate(2017, 6, 2), grades[5].date)
    }

    @Test fun getTeacherTest() {
        assertEquals("Amelia Stępień", grades[0].teacher)
        assertEquals("Patryk Maciejewski", grades[1].teacher)
        assertEquals("Klaudia Dziedzic", grades[3].teacher)
        assertEquals("Oliwia Woźniak", grades[5].teacher)
    }
}
