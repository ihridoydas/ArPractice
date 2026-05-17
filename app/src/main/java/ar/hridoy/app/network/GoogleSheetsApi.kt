package ar.hridoy.app.network

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
data class BridgeRequest(
    val action: String,
    val id: Int,
    val name: String,
    val imageAssetPath: String,
    val videoUrl: String,
    val active: Boolean,
    val rowIndex: Int? = null
)

@JsonClass(generateAdapter = true)
data class SheetResponse(
    val values: List<List<String>>?
)

@JsonClass(generateAdapter = true)
data class ValueRange(
    val values: List<List<String>>
)

@JsonClass(generateAdapter = true)
data class BatchUpdateRequest(
    val requests: List<Request>
)

@JsonClass(generateAdapter = true)
data class Request(
    val deleteDimension: DeleteDimensionRequest? = null
)

@JsonClass(generateAdapter = true)
data class DeleteDimensionRequest(
    val range: DimensionRange
)

@JsonClass(generateAdapter = true)
data class DimensionRange(
    val sheetId: Int = 0,
    val dimension: String = "ROWS",
    val startIndex: Int,
    val endIndex: Int
)
