/*
* MIT License
*
* Copyright (c) 2026 Hridoy Chandra Das
*
* Permission is hereby granted, free of charge, to any person obtaining a copy
* of this software and associated documentation files (the "Software"), to deal
* in the Software without restriction, including without limitation the rights
* to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the Software is
* furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in all
* copies or substantial portions of the Software.
*
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
* AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
* SOFTWARE.
*
*/
package ar.hridoy.app.util

import android.content.Context
import android.telephony.TelephonyManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import ar.hridoy.app.datastore.Language
import java.util.Locale

object Utils {
    fun applyLanguage(
        context: Context,
        language: Language,
    ) {
        val targetCode = when (language) {
            Language.SYSTEM -> {
                val telephonyManager =
                    context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

                val countryIso = telephonyManager
                    ?.networkCountryIso
                    ?.takeIf { it.isNotBlank() }
                    ?.uppercase()

                when (countryIso) {
                    "BD" -> "bn"
                    "JP" -> "ja"
                    else -> Locale.getDefault().language
                }
            }
            Language.ENGLISH -> "en"
            Language.JAPANESE -> "ja"
            Language.BENGALI -> "bn"
            else -> "en"
        }

        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val currentCode = if (!currentLocales.isEmpty) currentLocales.get(0)?.language else null

        // Log to debug
        // android.util.Log.d("LanguageSync", "Current: $currentCode, Target: $targetCode")

        if (currentCode != targetCode) {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(targetCode),
            )
        }
    }

    fun changeLanguage(code: String) {
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val currentCode = if (!currentLocales.isEmpty) currentLocales.get(0)?.language else null

        if (currentCode != code) {
            val appLocale = LocaleListCompat.forLanguageTags(code)
            AppCompatDelegate.setApplicationLocales(appLocale)
        }
    }
}
