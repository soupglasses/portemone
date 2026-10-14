// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.finnes.portemone.cards.CardManagementModel
import dev.finnes.portemone.cards.LocalCardRepository
import dev.finnes.portemone.diagnostics.AppDiagnostics
import dev.finnes.portemone.imports.localizedPassNotice
import dev.finnes.portemone.location.GeofenceCoordinator
import dev.finnes.portemone.location.LocationSettings
import dev.finnes.portemone.ui.theme.WalletTheme
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class CardActivity : ComponentActivity() {
    private val deviceUnlocked = mutableStateOf(false)
    private val screenState = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val screenOff = intent.action == Intent.ACTION_SCREEN_OFF
            val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
            deviceUnlocked.value = !screenOff && !locked
            AppDiagnostics.event("CardUnlock", "Screen state: screenOff=$screenOff locked=$locked")
        }
    }

    override fun onResume() {
        super.onResume()
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        deviceUnlocked.value = !locked
        AppDiagnostics.event("CardUnlock", "Resume: locked=$locked")
    }

    override fun onDestroy() {
        unregisterReceiver(screenState)
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Screen-off invalidates disclosure even when this show-when-locked activity survives.
        androidx.core.content.ContextCompat.registerReceiver(
            this, screenState,
            android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        enableEdgeToEdge()
        stopService(Intent(this, FloatingCardService::class.java))
        val demoType =
            intent.getStringExtra(TYPE)?.let { runCatching { BarcodeType.valueOf(it) }.getOrNull() }
                ?: SampleCard.card.barcodeType
        val cardId = intent.getStringExtra(CARD_ID)
        val openedForScan = intent.getBooleanExtra(OPEN_ENLARGED, false)
        val settings = LocationSettings(this)
        val initialCard =
            if (cardId == null || cardId == SampleCard.card.id) SampleCard.card.copy(barcodeType = demoType)
            else settings.cards().find { it.id == cardId } ?: run { finish(); return }
        val management = androidx.lifecycle.ViewModelProvider(this)[CardManagementModel::class.java]
        val repository = LocalCardRepository.get(this)
        val keyguard = getSystemService(KeyguardManager::class.java)
        AppDiagnostics.event("Card", "Opened ${initialCard.id}; ${initialCard.barcodeType}")
        setContent {
            WalletTheme {
                var settingsRevision by remember { mutableIntStateOf(0) }
                DisposableEffect(settings) {
                    val unsubscribe = settings.observe { settingsRevision++ }
                    onDispose { unsubscribe() }
                }
                var deleting by remember { mutableStateOf(false) }
                var confirmDelete by remember { mutableStateOf(false) }
                var deleteError by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()
                val revision by repository.revision.collectAsStateWithLifecycle()
                val card by produceState(initialCard, revision, settingsRevision) {
                    val latest = withContext(Dispatchers.IO) {
                        settings.cards().find { it.id == initialCard.id }
                    }
                    if (latest != null) value = latest
                    else if (initialCard.id != SampleCard.card.id && !deleting) finish()
                }
                if (confirmDelete && deviceUnlocked.value) AlertDialog(
                    onDismissRequest = { if (!deleting) confirmDelete = false },
                    title = { Text(stringResource(R.string.cardactivity_delete_test_card)) },
                    text = {
                        Text(
                            deleteError
                                ?: getString(R.string.removes_this_temporary_card_its_store_area_and_its)
                        )
                    },
                    confirmButton = {
                        TextButton(enabled = !deleting, onClick = {
                            deleting = true
                            scope.launch {
                                try {
                                    GeofenceCoordinator(this@CardActivity).removeTestStore()
                                    finish()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    AppDiagnostics.event(
                                        "Card",
                                        "Test card deletion registration update failed",
                                        error
                                    )
                                    if (settings.cards()
                                            .none { it.id == TestCard.card.id }
                                    ) finish()
                                    else deleteError =
                                        getString(R.string.could_not_delete_the_test_card_try_again)
                                } finally {
                                    deleting = false
                                }
                            }
                        }) { Text(if (deleting) getString(R.string.deleting) else getString(R.string.delete)) }
                    },
                    dismissButton = {
                        TextButton(
                            enabled = !deleting,
                            onClick = {
                                confirmDelete = false
                            }) { Text(stringResource(R.string.cancel)) }
                    })
                var scannerRequested by rememberSaveable { mutableStateOf(openedForScan && initialCard.barcodeType != BarcodeType.NONE) }
                LaunchedEffect(card.barcodeType) {
                    if (card.barcodeType == BarcodeType.NONE) scannerRequested = false
                }
                val unlocked by deviceUnlocked
                val enlarged =
                    card.barcodeType != BarcodeType.NONE && (scannerRequested || !unlocked)

                fun closeCard() {
                    if (this@CardActivity is NotificationCardActivity) finishAndRemoveTask()
                    else finish()
                }

                fun leaveScanner() {
                    if (openedForScan || !unlocked) closeCard() else scannerRequested = false
                }

                fun openDetails() {
                    val locked = keyguard.isKeyguardLocked
                    AppDiagnostics.event("CardUnlock", "Details requested: locked=$locked")
                    if (!locked) {
                        deviceUnlocked.value = true
                        scannerRequested = false
                    } else {
                        keyguard.requestDismissKeyguard(
                            this@CardActivity,
                            object : KeyguardManager.KeyguardDismissCallback() {
                                override fun onDismissSucceeded() {
                                    val locked = keyguard.isKeyguardLocked
                                    AppDiagnostics.event(
                                        "CardUnlock",
                                        "Dismiss succeeded: locked=$locked"
                                    )
                                    deviceUnlocked.value = !locked
                                    // Unlock observations can arrive separately; keep the request
                                    // cleared while deviceUnlocked continues to gate private details.
                                    scannerRequested = false
                                }

                                override fun onDismissError() {
                                    AppDiagnostics.event(
                                        "CardUnlock",
                                        "Dismiss error: locked=${keyguard.isKeyguardLocked}"
                                    )
                                }

                                override fun onDismissCancelled() {
                                    AppDiagnostics.event(
                                        "CardUnlock",
                                        "Dismiss cancelled: locked=${keyguard.isKeyguardLocked}"
                                    )
                                }
                            })
                    }
                }

                val darkTheme = isSystemInDarkTheme()
                val scroll = rememberLazyListState()
                SideEffect {
                    if (!enlarged && requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = enlarged || !darkTheme
                        isAppearanceLightNavigationBars = enlarged || !darkTheme
                    }
                    window.attributes =
                        window.attributes.apply { screenBrightness = if (enlarged) 1f else -1f }
                    // The normal card face is also a checkout surface.
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                BackHandler(enabled = enlarged || openedForScan) {
                    if (enlarged) leaveScanner() else closeCard()
                }
                Scaffold(
                    containerColor = if (enlarged) Color.White else MaterialTheme.colorScheme.surfaceContainerLow,
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    "${card.brandName} ${card.cardName}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }, navigationIcon = {
                                BackButton(onClick = { if (enlarged) leaveScanner() else closeCard() })
                            }, actions = {
                                if (enlarged) {
                                    FilledTonalIconButton(
                                        modifier = Modifier.padding(end = 8.dp).size(48.dp),
                                        onClick = {
                                            requestedOrientation =
                                                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                        }
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_rotate),
                                            contentDescription = stringResource(R.string.rotate_code)
                                        )
                                    }
                                }
                            }, colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = if (enlarged) Color.White else MaterialTheme.colorScheme.surfaceContainerLow,
                                titleContentColor = if (enlarged) Color.Black else MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        if (enlarged) {
                            Column(
                                Modifier.fillMaxSize().padding(vertical = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                card.statusLabelRes()?.let { resources.getString(it) }
                                    ?.let { Text(it, color = Color.Red, modifier = Modifier.padding(horizontal = 20.dp)) }
                                EnlargedCode(card, Modifier.fillMaxWidth().weight(1f))
                                Text(
                                    card.rawValue,
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    color = Color.Black,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    stringResource(R.string.screen_brightness_increased),
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    color = Color.DarkGray,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton(onClick = { openDetails() }) { Text(stringResource(R.string.card_details)) }
                            }
                        } else if (!unlocked) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Button(onClick = { openDetails() }) { Text(stringResource(R.string.unlock_to_view_card)) }
                            }
                        } else {
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                val wide = maxWidth >= 840.dp

                                @Composable
                                fun Face(modifier: Modifier = Modifier) {
                                    Column(
                                        modifier.padding(horizontal = CardCarouselSidePadding),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CardFace(
                                            card,
                                            Modifier.widthIn(max = CardFaceMaxWidth).fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable(
                                                    role = Role.Button,
                                                    onClickLabel = getString(R.string.enlarge_code)
                                                ) {
                                                    if (card.barcodeType != BarcodeType.NONE) scannerRequested =
                                                        true
                                                }
                                                .semantics {
                                                    contentDescription =
                                                        getString(
                                                            R.string.cardactivity_enlarge_code,
                                                            card.brandName
                                                        )
                                                })
                                        if (card.barcodeType != BarcodeType.NONE) Text(
                                            stringResource(R.string.tap_card_to_enlarge_code),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        card.statusLabelRes()?.let { resources.getString(it) }
                                            ?.let {
                                                Text(
                                                    it,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        if (card.id != TestCard.card.id && card.id != SampleCard.card.id) {
                                            CardActions(
                                                card,
                                                management,
                                                onDeleted = { finish() })
                                        }
                                        if (card.id == TestCard.card.id) TextButton(
                                            onClick = { confirmDelete = true },
                                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Text(stringResource(R.string.delete_test_card))
                                        }
                                    }
                                }

                                fun LazyListScope.locations() {
                                    if (card.id != SampleCard.card.id) {
                                        item {
                                            Text(
                                                stringResource(R.string.store_locations),
                                                Modifier.walletTextColumn(),
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                        }
                                        if (card.stores.isEmpty()) item {
                                            Text(
                                                stringResource(R.string.no_saved_locations_add_one_in_edit_card),
                                                Modifier.walletTextColumn(),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                        items(card.stores, key = { "location_${it.id}" }) { store ->
                                            SavedLocationCard(
                                                store,
                                                card.brandColor,
                                                Modifier.walletTextColumn()
                                            )
                                        }
                                        item {
                                            Column(
                                                Modifier.walletTextColumn(),
                                                verticalArrangement = Arrangement.spacedBy(16.dp)
                                            ) {
                                                CardFields(card)
                                                card.source?.let { source ->
                                                    Text(
                                                        if (source.warnings.isEmpty()) getString(R.string.original_import_signature_verified_offline_revocation_was_not_checked)
                                                        else getString(
                                                            R.string.original_import_needs_review,
                                                            source.warnings.joinToString(" ") {
                                                                it.localizedPassNotice(resources)
                                                            }),
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                }
                                            }
                                        }
                                    } else item {
                                        Text(
                                            stringResource(R.string.demo_membership),
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    }
                                }
                                if (wide) {
                                    Row(
                                        Modifier.fillMaxSize().padding(24.dp),
                                        horizontalArrangement = Arrangement.spacedBy(32.dp)
                                    ) {
                                        Face(Modifier.weight(1f))
                                        Box(Modifier.weight(1f)) {
                                            LazyColumn(
                                                Modifier.fillMaxSize().testTag("card_details"),
                                                contentPadding = PaddingValues(bottom = 24.dp),
                                                verticalArrangement = Arrangement.spacedBy(16.dp)
                                            ) {
                                                locations()
                                            }
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        Modifier.fillMaxSize().testTag("card_details"),
                                        state = scroll,
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        contentPadding = PaddingValues(vertical = 20.dp),
                                        verticalArrangement = Arrangement.spacedBy(28.dp)
                                    ) {
                                        item {
                                            Face(
                                                Modifier.widthIn(max = CardCarouselMaxWidth)
                                                    .fillMaxWidth()
                                            )
                                        }
                                        locations()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val TYPE = "barcode_type"
        const val CARD_ID = "card_id"
        const val OPEN_ENLARGED = "open_enlarged"

        fun scanIntent(context: Context, card: Card) = intent(context, card)
            .setClass(context, NotificationCardActivity::class.java)
            .setData(Uri.parse("wallet://scan/${card.id}"))
            .putExtra(OPEN_ENLARGED, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        fun intent(context: Context, card: Card) = Intent(context, CardActivity::class.java)
            // Distinct URIs prevent a later card notification from replacing this membership.
            .setData(Uri.parse("wallet://card/${card.id}"))
            .putExtra(CARD_ID, card.id)
            .putExtra(TYPE, card.barcodeType.name)
    }
}

// A notification scan has its own task so the launcher always returns to the wallet.
class NotificationCardActivity : CardActivity()

@Composable
private fun EnlargedCode(card: Card, modifier: Modifier) {
    val resources = LocalResources.current
    var error by remember(
        card.rawValue,
        card.barcodeType,
        card.barcodeEncoding
    ) { mutableStateOf<String?>(null) }
    Box(modifier, contentAlignment = Alignment.Center) {
        key(card.rawValue, card.barcodeType, card.barcodeEncoding) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                ImageView(context).apply {
                    id = R.id.barcode
                    scaleType = ImageView.ScaleType.CENTER
                    setBackgroundColor(android.graphics.Color.WHITE)
                    contentDescription =
                        resources.getString(
                            R.string.membership_code_description,
                            card.brandName,
                            resources.getString(card.barcodeType.labelRes)
                        )
                    addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                        val width = right - left
                        val height = bottom - top
                        if (width > 0 && height > 0 && (drawable == null || width != oldRight - oldLeft || height != oldBottom - oldTop)) {
                            val codeWidth =
                                if (card.barcodeType.square) min(width, height) else width
                            val codeHeight = if (card.barcodeType.square) codeWidth else min(
                                height,
                                (180 * resources.displayMetrics.density).toInt()
                            )
                            runCatching {
                                if (card.barcodeType.square) BarcodeRenderer.fitSquare(
                                    card,
                                    codeWidth
                                ).bitmap
                                else BarcodeRenderer.render(
                                    card.rawValue,
                                    card.barcodeType,
                                    codeWidth,
                                    codeHeight,
                                    card.barcodeEncoding
                                )
                            }
                                .onSuccess { setImageBitmap(it); error = null }
                                .onFailure {
                                    setImageDrawable(null)
                                    error =
                                        resources.getString(R.string.code_does_not_fit_rotate_your_phone_to_provide)
                                    contentDescription = error
                                }
                        }
                    }
                }
            })
        }
        error?.let { Text(it, color = Color.Black, modifier = Modifier.padding(16.dp)) }
    }
}
