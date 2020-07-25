package io.github.wulkanowy.sdk.scrapper.school

import io.github.wulkanowy.sdk.scrapper.getEmptyIfDash

fun SchoolAndTeachersResponse.mapToTeachers() = teachers.map { item ->
    item.name.split(",").map { namePart ->
        item.copy(
            name = namePart.substringBefore(" [").getEmptyIfDash().trim(),
            short = namePart.substringAfter("[").substringBefore("]").getEmptyIfDash(),
            subject = item.subject.trim()
        )
    }.asReversed()
}.flatten().sortedWith(compareBy({ it.subject }, { it.name }))
