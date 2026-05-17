/*
* MIT License
*
* Copyright (c) 2024 Hridoy Chandra Das
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
package ar.hridoy.app

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import ar.hridoy.app.common.DURATION
import ar.hridoy.app.common.VALUES_X
import ar.hridoy.app.common.VALUES_Y
import ar.hridoy.app.common.utils.RootUtil
import ar.hridoy.app.datastore.ThemePreferences
import ar.hridoy.app.local.language.LanguageDataStore
import ar.hridoy.app.local.theme.ThemeLocalDataStore
import ar.hridoy.app.theme.ArPracticeTheme
import ar.hridoy.app.theme.splashScreen.SplashViewModel
import ar.hridoy.app.ui.MainAnimationNavHost
import ar.hridoy.app.util.Utils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    companion object {
        private val Tag = MainActivity::class.java.simpleName
    }

    private val splashViewModel: SplashViewModel by viewModels()
    private lateinit var languageDataStore: LanguageDataStore
    private lateinit var themeDataStore: ThemeLocalDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize DataStores immediately
        languageDataStore = LanguageDataStore(this)
        themeDataStore = ThemeLocalDataStore(this)

        configureEdgeToEdgeWindow()

        Timber.tag(Tag).d("onCreate")

        installSplashScreen().apply {
            setKeepOnScreenCondition {
                !splashViewModel.isLoading.value
            }
            setOnExitAnimationListener { screen ->
                val zoomX = ObjectAnimator.ofFloat(
                    screen.iconView,
                    View.SCALE_X,
                    VALUES_X,
                    VALUES_Y,
                )
                zoomX.interpolator = OvershootInterpolator()
                zoomX.duration = DURATION
                zoomX.doOnEnd { screen.remove() }

                val zoomY = ObjectAnimator.ofFloat(
                    screen.iconView,
                    View.SCALE_Y,
                    VALUES_X,
                    VALUES_Y,
                )
                zoomY.interpolator = OvershootInterpolator()
                zoomY.duration = DURATION
                zoomY.doOnEnd { screen.remove() }

                zoomX.start()
                zoomY.start()
            }
        }

        enableEdgeToEdge()

        setContent {
            val languageState = languageDataStore.getLanguage.collectAsState(initial = null)
            val themeMode by themeDataStore.themeMode
                .collectAsState(initial = ThemePreferences.ThemeMode.SYSTEM)

            // Asynchronous Root Check to prevent ANR
            LaunchedEffect(Unit) {
                val isRooted = withContext(Dispatchers.IO) {
                    RootUtil.isDeviceRooted()
                }
                if (isRooted) {
                    Timber.tag(Tag).e("onCreate - Rooted device detected.")
                    finish()
                }
            }

            languageState.value?.let { language ->
                LaunchedEffect(language) {
                    Utils.applyLanguage(this@MainActivity, language)
                }
            }

            val isDarkTheme = when (themeMode) {
                ThemePreferences.ThemeMode.DARK -> true
                ThemePreferences.ThemeMode.LIGHT -> false
                ThemePreferences.ThemeMode.SYSTEM -> isSystemInDarkTheme()
                else -> false
            }
            ArPracticeTheme(useDarkTheme = isDarkTheme) {
                ChangeSystemBarsTheme(!isDarkTheme)
                Surface(
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainAnimationNavHost(languageDataStore, themeDataStore)
                }
            }
        }
    }

    private fun configureEdgeToEdgeWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    @Composable
    private fun ChangeSystemBarsTheme(lightTheme: Boolean) {
        val barColor = MaterialTheme.colorScheme.background.toArgb()
        LaunchedEffect(lightTheme) {
            if (lightTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.light(
                        barColor,
                        barColor,
                    ),
                    navigationBarStyle = SystemBarStyle.light(
                        barColor,
                        barColor,
                    ),
                )
            } else {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.dark(
                        barColor,
                    ),
                    navigationBarStyle = SystemBarStyle.dark(
                        barColor,
                    ),
                )
            }
        }
    }
}
