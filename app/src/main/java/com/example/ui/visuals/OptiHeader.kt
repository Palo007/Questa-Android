package com.example.ui.visuals

import android.graphics.BitmapFactory
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entities.CharacterEntity
import com.example.data.RpgEngine
import com.example.ui.settings.AvatarImage
import com.example.ui.settings.AvatarStore
import com.example.ui.settings.SyncBadgeMode
import com.example.ui.settings.SyncBadgeState
import com.example.ui.theme.OptiRootTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `header` background (index.html:33): `linear-gradient(180deg,#2a1947,#36205c)`. */
val HeaderGradient = listOf(Color(0xFF2A1947), OptiRootTokens.Bg)

/**
 * Opti `<header>` (index.html:33-81, 1047-1066) filled by `renderStats` (app.js:3105-3140):
 * the avatar (image over the emoji, WP-8.15 hand-off), name, "Level N · Class", the gold coin
 * with `Math.floor(gold)`, the gear, then the HP and XP bars. `body.lowhp` pulses a 2 dp inset
 * HP ring on the header (1.4 s).
 */
@Composable
fun OptiHeader(
    character: CharacterEntity,
    onGear: () -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit = {},
    paused: Boolean = false,
    gearStale: Boolean = false,
    avatar: AvatarImage? = rememberAvatarImage(),
    animate: Boolean = true,
    syncBadge: SyncBadgeState = SyncBadgeState.HIDDEN,
    onSyncBadgeClick: () -> Unit = {},
) {
    val low = isLowHp(character.hp, character.maxHp)
    val ring = if (low && animate) {
        val tr = rememberInfiniteTransition(label = "hpwarn")
        tr.animateFloat(0f, 1f, infiniteRepeatable(tween(OptiDims.LOWHP_PULSE_MS / 2, easing = CssEaseInOut), RepeatMode.Reverse), label = "hpwarnA").value
    } else if (low) 1f else 0f
    val need = RpgEngine.xpToLevel(character.lvl).toDouble()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(HeaderGradient))
            .drawWithContent {
                drawContent()
                // border-bottom:1px solid var(--line)
                val px = 1.dp.toPx()
                drawRect(OptiRootTokens.Line, Offset(0f, size.height - px), Size(size.width, px))
                if (ring > 0f) {
                    val w = 2.dp.toPx()
                    drawRect(OptiRootTokens.Hp.copy(alpha = ring), Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
                }
            }
            .padding(start = OptiDims.HEADER_PAD_H.dp, end = OptiDims.HEADER_PAD_H.dp, top = OptiDims.HEADER_PAD_TOP.dp, bottom = (OptiDims.HEADER_PAD_BOTTOM + 1).dp) // + border-bottom
            .testTag("opti_header"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OptiDims.AVATAR_GAP.dp)) {
            Avatar(character.face, avatar, paused, animate)
            Column(Modifier.weight(1f)) {
                Text(character.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = OptiRootTokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("char_name"))
                Text(
                    buildAnnotatedString {
                        append("Level ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(character.lvl.toString()) }
                        append(" · " + character.cls)
                    },
                    fontSize = 12.sp, color = OptiRootTokens.Muted, modifier = Modifier.testTag("char_level"),
                )
            }
            // .coins: 13 sp bold, the 16 dp coin with margin-right 2 + the span's gap 3.
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoinIcon(16, Modifier.padding(end = 5.dp))
                Text(goldLabel(character.gold), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = OptiRootTokens.Ink, modifier = Modifier.testTag("stat_gold"))
                // renderStats pause badge (app.js:3127-3139), appended inside the gold span:
                // `.pauseBadge` 10 sp muted, margin-left 8, opacity .8.
                if (paused) {
                    Text(
                        "⏸️ Paused", fontSize = 10.sp, color = OptiRootTokens.Muted, softWrap = false,
                        modifier = Modifier.padding(start = 8.dp).alpha(0.8f).testTag("pause_badge"),
                    )
                }
            }
            // CR-KT-018: #syncBadge (index.html:1103), before #refreshBtn as in Opti (T10a).
            SyncBadge(syncBadge, onSyncBadgeClick)
            // #refreshBtn (index.html:1054, css:65-66): the gear's look, "↻".
            Text(
                "↻",
                fontSize = 18.sp,
                color = OptiRootTokens.Muted,
                modifier = Modifier
                    .alpha(0.7f)
                    .clickable(onClick = onRefresh)
                    .semantics { role = Role.Button; contentDescription = "Refresh" }
                    .padding(4.dp)
                    .testTag("refresh_button"),
            )
            // #gearBtn: 18 sp muted, opacity .7, padding 4; `.stale` (css:68-69): opacity 1 and the
            // 2 s `gearStale` pulse, muted -> hp with an 8 dp hp glow at 50 %.
            val staleT = if (gearStale && animate) {
                val tr = rememberInfiniteTransition(label = "gearStale")
                tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = CssEase), RepeatMode.Reverse), label = "gearStaleT").value
            } else if (gearStale) 1f else 0f
            val px = with(androidx.compose.ui.platform.LocalDensity.current) { 1.dp.toPx() }
            Text(
                "⚙",
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 18.sp,
                    color = androidx.compose.ui.graphics.lerp(OptiRootTokens.Muted, OptiRootTokens.Hp, staleT),
                    shadow = if (staleT > 0f) androidx.compose.ui.graphics.Shadow(OptiRootTokens.Hp.copy(alpha = staleT), Offset.Zero, 8 * px) else null,
                ),
                modifier = Modifier
                    .alpha(if (gearStale) 1f else 0.7f)
                    .clickable(onClick = onGear)
                    .semantics {
                        role = Role.Button; contentDescription = "Settings"
                        if (gearStale) stateDescription = GEAR_STALE_STATE
                    }
                    .padding(4.dp)
                    .testTag("edit_character_button"),
            )
        }
        Column(Modifier.padding(top = OptiDims.BARS_TOP.dp), verticalArrangement = Arrangement.spacedBy(OptiDims.BARS_GAP.dp)) {
            OptiStatBar(barFraction(character.hp, character.maxHp), HpFill, hpLabel(character.hp, character.maxHp), Modifier.testTag("hp_bar"), animate)
            OptiStatBar(barFraction(character.xp.toDouble(), need), XpFill, xpLabel(character.xp.toDouble(), need), Modifier.testTag("xp_bar"), animate)
        }
    }
}

/** `.avatar` (index.html:36-38): 46 dp, radius 10, `--panel2`, line border, the face at 24 sp. */
@Composable
fun Avatar(face: String, image: AvatarImage?, paused: Boolean = false, animate: Boolean = true) {
    val shape = RoundedCornerShape(OptiDims.AVATAR_RADIUS.dp)
    val bmp = rememberDecoded(image)
    Box(
        modifier = Modifier
            .size(OptiDims.AVATAR.dp)
            .background(OptiRootTokens.Panel2, shape)
            .border(1.dp, OptiRootTokens.Line, shape)
            .testTag("avatar"),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            // background-size:cover; background-position:center (app.js:3108-3109)
            Image(bmp, contentDescription = "Avatar image", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(shape).testTag("avatar_image"))
        }
        // The face (textContent is '' with an image) and `.avatar.paused::after` (index.html:40):
        // ' Zzz' 10 sp muted, margin-left 2, `zzz` 1.5 s (opacity .4 -> 1, up 2 dp). The avatar has
        // no overflow:hidden, so the text may spill past the 46 dp box, as in Opti.
        Row(Modifier.wrapContentWidth(unbounded = true), verticalAlignment = Alignment.CenterVertically) {
            if (bmp == null) Text(face, fontSize = OptiDims.AVATAR_SP.sp, softWrap = false, modifier = Modifier.testTag("avatar_face"))
            if (paused) {
                val (a, dy) = if (animate) {
                    val tr = rememberInfiniteTransition(label = "zzz")
                    val p = tr.animateFloat(0f, 1f, infiniteRepeatable(tween(750, easing = CssEaseInOut), RepeatMode.Reverse), label = "zzzT").value
                    (0.4f + 0.6f * p) to (-2f * p)
                } else 1f to 0f
                Text(
                    " Zzz", fontSize = 10.sp, color = OptiRootTokens.Muted, softWrap = false,
                    modifier = Modifier.padding(start = 2.dp).offset(y = dy.dp).alpha(a).testTag("avatar_zzz"),
                )
            }
        }
    }
}

/** CR-KT-018: `#syncBadge .sbDot` colors (index.html:73-75). */
val SyncDotUnsynced = Color(0xFFF7A24E)
val SyncDotError = Color(0xFFF74E52)

/**
 * CR-KT-018: Opti `#syncBadge` (index.html:70-76, 1103; app.js:7473-7495). Muted 11 sp, padding 4,
 * gap 4, max-width 34vw. UNSYNCED / ERROR: the 9 dp dot only (no visible text). STALE: the label
 * only (no dot). HIDDEN: nothing at all, no space kept. The tap opens Settings (Opti `openSettings()`).
 * The accessible name is Opti's title / aria-label ([SyncBadgeState.tooltip]).
 */
@Composable
fun SyncBadge(state: SyncBadgeState, onClick: () -> Unit) {
    if (state.mode == SyncBadgeMode.HIDDEN) return
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.34f).dp
    Row(
        modifier = Modifier
            .widthIn(max = maxW)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button; contentDescription = state.tooltip }
            .padding(4.dp)
            .testTag("sync_badge"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (state.mode) {
            SyncBadgeMode.UNSYNCED, SyncBadgeMode.ERROR -> Box(
                Modifier
                    .size(9.dp)
                    .background(if (state.mode == SyncBadgeMode.ERROR) SyncDotError else SyncDotUnsynced, CircleShape)
                    .testTag("sync_badge_dot")
            )
            SyncBadgeMode.STALE -> Text(
                state.label, fontSize = 11.sp, color = OptiRootTokens.Muted, maxLines = 1, softWrap = false,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("sync_badge_label"),
            )
            SyncBadgeMode.HIDDEN -> Unit
        }
    }
}

/** The gear's state description while `#gearBtn.stale` (no export for over 7 days). */
const val GEAR_STALE_STATE = "Backup overdue"

/** WP-8.15 hand-off: the app's avatar image (`AvatarStore.forApp(ctx).image`). */
@Composable
fun rememberAvatarImage(): AvatarImage? {
    val ctx = LocalContext.current.applicationContext
    val store = remember(ctx) { AvatarStore.forApp(ctx) }
    return store.image.collectAsState().value
}

/** Decodes [image] off the main thread, keyed on its `version` (a new file = a new bitmap). */
@Composable
private fun rememberDecoded(image: AvatarImage?): ImageBitmap? {
    val v by produceState<ImageBitmap?>(null, image?.file, image?.version) {
        value = if (image == null) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(image.file.path)?.asImageBitmap() }.getOrNull()
        }
    }
    return v
}
