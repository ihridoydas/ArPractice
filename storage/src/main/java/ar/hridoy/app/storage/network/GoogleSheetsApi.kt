package ar.hridoy.app.storage.network

import com.squareup.moshi.JsonClass
import retrofit2.http.*

interface GoogleSheetsApi {
    // Fetch data via Script
    @GET
    suspend fun getSheetValues(
        @Url url: String
    ): SheetResponse

    // Perform actions (Add/Edit/Delete) via Script
    @POST
    suspend fun executeAction(
        @Url url: String,
        @Body body: BridgeRequest
    ): Map<String, Any>
}

@JsonClass(generateAdapter = true)
data class SheetResponse(
    val values: List<List<String>>?
)

@JsonClass(generateAdapter = true)
data class BridgeRequest(
    val action: String,
    val id: Int,
    val name: String,
    val imageAssetPath: String,
    val videoUrl: String,
    val active: Boolean,
    val rowIndex: Int? = null
)
