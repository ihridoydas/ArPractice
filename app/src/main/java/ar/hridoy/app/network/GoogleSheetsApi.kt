package ar.hridoy.app.network

import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface GoogleSheetsApi {
    @GET("v4/spreadsheets/{spreadsheetId}/values/{range}")
    suspend fun getSheetValues(
        @Path("spreadsheetId") spreadsheetId: String,
        @Path("range") range: String,
        @Query("key") apiKey: String
    ): SheetResponse
}

@JsonClass(generateAdapter = true)
data class SheetResponse(
    val values: List<List<String>>?
)
