/*
 * Foxy location picker — the same page UAC POW uses.
 *
 * Layout, search field, section labels, rows and selection feedback are the POW components
 * (ToolPageBackground / TorCountryTopBar / CountrySectionLabel / TorCountryRow) so switching
 * engines does not switch design language.
 */
package com.uacspoofer.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.engine.foxy.FoxyEngineStore
import com.uacspoofer.mobile.engine.foxy.GuardianClient
import com.uacspoofer.mobile.engine.pow.PowRegions
import com.uacspoofer.mobile.profiles.CountryMetadata
import com.uacspoofer.mobile.ui.theme.UacColors
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Automatic" for Foxy is Mozilla's own REC (recommended) record. */
private const val FOXY_AUTOMATIC = "REC"

@Composable
internal fun FoxyCountryScreen(onMenuClick: () -> Unit) {
    val context = LocalContext.current
    val isPersian = LocalHomePersian.current
    val store = remember(context) { FoxyEngineStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val connectionState by ConnectionStateStore.state.collectAsStateWithLifecycle()
    val nameLocale = if (isPersian) Locale("fa") else Locale.ENGLISH
    var query by rememberSaveable { mutableStateOf("") }
    var applying by remember { mutableStateOf(false) }
    var codes by remember { mutableStateOf<List<String>>(emptyList()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var snackbarJob by remember { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val selectedCode = settings.serverCountry.ifBlank { FOXY_AUTOMATIC }

    LaunchedEffect(Unit) {
        codes = withContext(Dispatchers.IO) {
            val countries = runCatching {
                GuardianClient().fetchCountries().takeIf { it.isNotEmpty() }
                    ?: GuardianClient.getBuiltInFallbackCountries()
            }.getOrDefault(GuardianClient.getBuiltInFallbackCountries())
            countries.map { it.code.uppercase(Locale.US) }
                .filter { it.isNotBlank() && it != FOXY_AUTOMATIC }
                .distinct()
                .sortedBy { PowRegions.name(it, nameLocale).lowercase(nameLocale) }
        }
    }

    val filtered = remember(codes, query, nameLocale) {
        if (query.isBlank()) codes else codes.filter { code ->
            PowRegions.name(code, nameLocale).contains(query.trim(), true) ||
                code.contains(query.trim(), true)
        }
    }
    val showAutomatic = query.isBlank() || PowRegions.matchesAutomatic(query)
    val accent = UacColors.DisconnectedBlue
    val selectedLabel = if (selectedCode == FOXY_AUTOMATIC) {
        homeText("Auto", "خودکار")
    } else {
        PowRegions.name(selectedCode, nameLocale)
    }

    fun notify(message: String) {
        snackbarJob?.cancel()
        snackbar.currentSnackbarData?.dismiss()
        snackbarJob = scope.launch { snackbar.showSnackbar(message) }
    }

    fun persistCountry(code: String) {
        val live = connectionState == ConnectionState.CONNECTED ||
            connectionState == ConnectionState.CONNECTING
        val next = settings.copy(serverCountry = code, serverCity = "").validated()
        if (next.serverCountry == settings.serverCountry) return
        store.save(next)
        val name = if (code == FOXY_AUTOMATIC) {
            if (isPersian) "خودکار" else "Automatic"
        } else {
            PowRegions.name(code, nameLocale)
        }
        if (live) {
            applying = true
            VpnController.applyFoxyExit(context)
            notify(if (isPersian) "دارم به $name وصل می‌شوم…" else "Switching to $name…")
            scope.launch {
                delay(8_000)
                applying = false
            }
        } else {
            notify(
                if (isPersian) "$name ذخیره شد، دفعهٔ بعد که وصل بشی همان می‌شود"
                else "$name saved — the next connection will use it",
            )
        }
    }

    LaunchedEffect(query) { listState.scrollToItem(0) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    CompositionLocalProvider(LocalTextStyle provides homeLocalizedTextStyle()) {
        ToolPageBackground(accent = accent) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                        ),
                ) {
                    WideSplitColumn(
                        headerPadding = 16.dp,
                        header = {
                            Spacer(Modifier.height(if (imeVisible) 4.dp else 8.dp))
                            TorCountryTopBar(
                                subtitle = selectedLabel,
                                compact = imeVisible,
                                onMenuClick = onMenuClick,
                            )
                        },
                    ) {
                        Spacer(Modifier.height(if (imeVisible) 8.dp else 10.dp))
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(15.dp),
                            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    keyboard?.hide()
                                    focusManager.clearFocus()
                                },
                            ),
                            placeholder = {
                                Text(
                                    homeText("Search country", "اسم کشور را بنویس"),
                                    color = UacColors.TextSecondary,
                                    fontSize = 14.sp,
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.Search, contentDescription = null, tint = UacColors.TextSecondary)
                            },
                            trailingIcon = if (query.isNotEmpty()) {
                                {
                                    RemoteIconButton(onClick = { query = "" }) {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription = homeText("Clear search", "پاک کن"),
                                            tint = UacColors.TextSecondary,
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = UacColors.TextPrimary,
                                unfocusedTextColor = UacColors.TextPrimary,
                                focusedBorderColor = accent,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                                cursorColor = accent,
                                focusedContainerColor = Color(0x99101C29),
                                unfocusedContainerColor = Color(0x66101C29),
                            ),
                        )
                        Spacer(Modifier.height(12.dp))
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = true),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(
                                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp,
                            ),
                        ) {
                            if (showAutomatic) {
                                item(key = "auto") {
                                    TorCountryRow(
                                        title = homeText("Auto", "خودکار"),
                                        subtitle = homeText(
                                            "Mozilla picks the recommended edge",
                                            "موزیلا نزدیک‌ترین سرور پیشنهادی را انتخاب می‌کند",
                                        ),
                                        country = null,
                                        selected = selectedCode == FOXY_AUTOMATIC,
                                        applying = applying && selectedCode == FOXY_AUTOMATIC,
                                        status = if (selectedCode == FOXY_AUTOMATIC) {
                                            homeText("Selected", "انتخاب شده")
                                        } else {
                                            null
                                        },
                                        onSelect = { persistCountry(FOXY_AUTOMATIC) },
                                    )
                                }
                            }
                            if (filtered.isNotEmpty()) {
                                item(key = "all-label") {
                                    CountrySectionLabel(homeText("All countries", "همه کشورها"))
                                }
                                itemsIndexed(filtered, key = { _, code -> code }) { _, code ->
                                    val selected = selectedCode == code
                                    TorCountryRow(
                                        title = PowRegions.name(code, nameLocale),
                                        subtitle = code.uppercase(Locale.US),
                                        country = CountryMetadata.resolve(code, null),
                                        selected = selected,
                                        applying = applying && selected,
                                        status = if (selected) homeText("Selected", "انتخاب شده") else null,
                                        onSelect = { persistCountry(code) },
                                    )
                                }
                            }
                        }
                    }
                }
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
