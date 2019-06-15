package io.github.wulkanowy.sdk.pojo

import io.github.wulkanowy.api.Api
import io.github.wulkanowy.sdk.Sdk

data class Student(
    val email: String,
    val symbol: String,
    val studentId: Int,
    val studentName: String,
    val schoolSymbol: String,
    val schoolName: String,
    val className: String,
    val classId: Int,
    val loginType: Api.LoginType,
    val loginMode: Sdk.Mode,
    val apiHost: String,
    val scrapperHost: String,
    val ssl: Boolean,
    val certificateKey: String,
    val certificate: String
)
