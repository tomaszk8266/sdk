package io.github.wulkanowy.sdk.notes

import com.google.gson.annotations.SerializedName
import io.github.wulkanowy.sdk.ApiRequest

data class NotesRequest(

    @SerializedName("IdOkresKlasyfikacyjny")
    val classificationPeriodId: Int,

    @SerializedName("IdUczen")
    val studentId: Int
) : ApiRequest()
