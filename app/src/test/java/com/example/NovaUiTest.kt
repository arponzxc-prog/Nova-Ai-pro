package com.example

import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.assistant.AssistantRuntimeState
import com.example.assistant.BackgroundSessionPhase
import com.example.data.AiRepository
import com.example.ui.about.AboutContent
import com.example.ui.hud.OrbRenderer
import com.example.ui.hud.OrbRendererPolicy
import com.example.ui.hud.OrbState
import com.example.ui.hud.OrbStateMapper
import com.example.ui.theme.NovaColor
import com.example.ui.theme.NovaThemeChoice
import com.example.ui.theme.NovaThemeId
import com.example.ui.theme.NovaThemes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NovaUiTest {

    // ---------------- colour maths / accessibility ----------------
    @Test fun contrast_knownValues() {
        assertEquals(21.0, NovaColor.contrast(Color.Black, Color.White), 0.01)
        assertEquals(1.0, NovaColor.contrast(Color.White, Color.White), 0.001)
    }

    @Test fun ensureContrast_reachesTarget_andLeavesGoodColoursAlone() {
        val bg = Color(0xFF070914)
        val low = Color(0xFF64748B) // JARVIS muted text, ~4.2:1
        val fixed = NovaColor.ensureContrast(low, bg, 4.5)
        assertTrue(NovaColor.contrast(fixed, bg) >= 4.5)
        assertEquals(Color.White, NovaColor.ensureContrast(Color.White, bg, 4.5))
        val lightBg = Color(0xFFFFF0EA)
        assertTrue(NovaColor.contrast(NovaColor.ensureContrast(Color(0xFFFF6A3D), lightBg, 4.5), lightBg) >= 4.5)
    }

    @Test fun everyTheme_meetsWcagAaForTextAndButtons() {
        NovaThemeId.entries.forEach { id ->
            val t = NovaThemes.tokens(id)
            assertTrue("$id primary text", NovaColor.contrast(t.textPrimary, t.bg) >= 7.0)
            assertTrue("$id secondary text", NovaColor.contrast(t.textSecondary, t.bg) >= 4.5)
            assertTrue("$id muted text", NovaColor.contrast(t.textMuted, t.bg) >= 4.5)
            assertTrue("$id accent as text", NovaColor.contrast(t.accentText, t.bg) >= 4.5)
            assertTrue("$id label on accent button", NovaColor.contrast(t.onAccent, t.accent1) >= 4.5)
            val cs = NovaThemes.colorScheme(t)
            assertTrue("$id onSurface on surface", NovaColor.contrast(cs.onSurface, cs.surface) >= 7.0)
            assertTrue("$id onPrimary on primary", NovaColor.contrast(cs.onPrimary, cs.primary) >= 4.5)
            assertTrue("$id onSurfaceVariant on container", NovaColor.contrast(cs.onSurfaceVariant, cs.surfaceContainerHigh) >= 4.5)
            assertEquals("$id surfaces must be opaque", 1f, cs.surfaceContainerHigh.alpha, 0f)
            assertEquals(1f, cs.surfaceVariant.alpha, 0f)
        }
    }

    @Test fun lightness_matchesIntent() {
        assertTrue(NovaThemes.Light.isLight && NovaThemes.Sunset.isLight)
        assertFalse(NovaThemes.Dark.isLight || NovaThemes.Red.isLight)
        assertTrue(NovaThemes.colorScheme(NovaThemes.Dark).background == NovaThemes.Dark.bg)
    }

    // ---------------- theme choice resolution ----------------
    @Test fun resolve_autoFollowsExistingThemeMode_explicitWins() {
        assertEquals(NovaThemeId.DARK, NovaThemes.resolve(NovaThemeChoice.AUTO, "dark", false))
        assertEquals(NovaThemeId.LIGHT, NovaThemes.resolve(NovaThemeChoice.AUTO, "light", true))
        assertEquals(NovaThemeId.DARK, NovaThemes.resolve(NovaThemeChoice.AUTO, "system", true))
        assertEquals(NovaThemeId.LIGHT, NovaThemes.resolve(NovaThemeChoice.AUTO, "system", false))
        assertEquals(NovaThemeId.RED, NovaThemes.resolve(NovaThemeChoice.RED, "light", false))
        assertEquals(NovaThemeId.SUNSET, NovaThemes.resolve(NovaThemeChoice.SUNSET, "dark", true))
    }

    @Test fun choiceKeys_roundTrip_unknownIsAuto() {
        NovaThemeChoice.entries.forEach { assertEquals(it, NovaThemeChoice.fromKey(it.key)) }
        assertEquals(NovaThemeChoice.AUTO, NovaThemeChoice.fromKey("garbage")); assertEquals(NovaThemeChoice.AUTO, NovaThemeChoice.fromKey(null))
    }

    // ---------------- DataStore persistence ----------------
    @Test fun themeChoice_persists_andDefaultsToAuto() = runBlocking {
        val repo = AiRepository(ApplicationProvider.getApplicationContext())
        assertEquals("auto", repo.novaThemeFlow.first())
        repo.saveNovaTheme("sunset")
        assertEquals("sunset", repo.novaThemeFlow.first())
        // a second repository instance (process-death stand-in) reads the same stored value
        assertEquals("sunset", AiRepository(ApplicationProvider.getApplicationContext()).novaThemeFlow.first())
        repo.saveNovaTheme("auto")
    }

    // ---------------- orb state mapping ----------------
    private fun rs(active: Boolean, phase: BackgroundSessionPhase, live: Boolean = false, status: String = "") =
        AssistantRuntimeState(isSessionActive = active, sessionPhase = phase, isLiveVoice = live, liveStatus = status)

    @Test fun orb_idleWhenNoSession_evenIfPhaseIsStale() {
        assertEquals(OrbState.IDLE, OrbStateMapper.from(rs(false, BackgroundSessionPhase.SPEAKING)))
        assertEquals(OrbState.IDLE, OrbStateMapper.from(AssistantRuntimeState()))
    }

    @Test fun orb_followsPhase() {
        assertEquals(OrbState.LISTENING, OrbStateMapper.from(rs(true, BackgroundSessionPhase.LISTENING)))
        assertEquals(OrbState.THINKING, OrbStateMapper.from(rs(true, BackgroundSessionPhase.THINKING)))
        assertEquals(OrbState.SPEAKING, OrbStateMapper.from(rs(true, BackgroundSessionPhase.SPEAKING)))
        assertEquals(OrbState.THINKING, OrbStateMapper.from(rs(true, BackgroundSessionPhase.WAITING_CONFIRMATION)))
        assertEquals(OrbState.IDLE, OrbStateMapper.from(rs(true, BackgroundSessionPhase.ERROR)))
    }

    @Test fun orb_geminiLiveConnectionStatesShowAsThinking() {
        assertEquals(OrbState.THINKING, OrbStateMapper.from(rs(true, BackgroundSessionPhase.LISTENING, live = true, status = "Reconnecting…")))
        assertEquals(OrbState.LISTENING, OrbStateMapper.from(rs(true, BackgroundSessionPhase.LISTENING, live = true, status = "")))
        // liveStatus only matters for Live sessions
        assertEquals(OrbState.LISTENING, OrbStateMapper.from(rs(true, BackgroundSessionPhase.LISTENING, live = false, status = "x")))
    }

    @Test fun orb_jsNamesMatchTheAsset() {
        val html = File("src/main/assets/nova_hud/orb.html").readText()
        OrbState.entries.forEach { assertTrue("orb.html has no state ${it.js}", html.contains("${it.js}:")) }
        NovaThemeId.entries.forEach { assertTrue("orb.html has no theme ${it.key}", html.contains("${it.key}:{bg:")) }
        assertTrue(html.contains("NovaOrb")); assertTrue(html.contains("NovaOrbControl"))
    }

    @Test fun rendererPolicy_fallsBackToCanvas() {
        assertEquals(OrbRenderer.WEBVIEW_3D, OrbRendererPolicy.choose(false, false, false, true))
        assertEquals(OrbRenderer.CANVAS, OrbRendererPolicy.choose(true, false, false, true))
        assertEquals(OrbRenderer.CANVAS, OrbRendererPolicy.choose(false, true, false, true))
        assertEquals(OrbRenderer.CANVAS, OrbRendererPolicy.choose(false, false, true, true))
        assertEquals(OrbRenderer.CANVAS, OrbRendererPolicy.choose(false, false, false, false))
    }

    // ---------------- WebView asset hardening (static) ----------------
    @Test fun orbAsset_isLocalOnly_andKeepsAttribution() {
        val html = File("src/main/assets/nova_hud/orb.html").readText()
        assertTrue(html.contains("Tech GPTx") && html.contains("TAMCL v1.0"))
        assertFalse("remote content", Regex("(src|href)\\s*=\\s*[\"']https?://").containsMatchIn(html))
        assertFalse(html.contains("fetch(")); assertFalse(html.contains("XMLHttpRequest"))
        assertTrue("web mic path must stay disabled", html.contains("async function startAudioReactive(){\n  return false;"))
        val three = File("src/main/assets/nova_hud/three.min.js")
        assertTrue(three.readText().take(300).contains("Three.js Authors") && three.length() > 100_000)
        assertFalse("no second copy of three.js", File("src/main/assets/three.min.js").exists())
    }

    // ---------------- About / credits / notices ----------------
    @Test fun about_containsRequiredCreditsExactly() {
        assertEquals("NOVA AI — Developer: ARPON", AboutContent.APP_AND_DEVELOPER)
        assertEquals(
            "UI design and voice/automation foundations based on JARVIS AI by Tech GPTx (https://github.com/techgptx/Playlist), licensed under TAMCL v1.0.",
            AboutContent.JARVIS_CREDIT
        )
        val all = AboutContent.allFixedText()
        assertTrue(all.contains("ARPON")); assertTrue(all.contains("ARPON X THE TIGER"))
        assertTrue(all.contains("Tech GPTx")); assertTrue(all.contains("TAMCL v1.0"))
        assertFalse("no identity-verification claims", all.contains("verified", ignoreCase = true) || all.contains("official", ignoreCase = true))
    }

    @Test fun bundledNoticesMatchRepositoryDocs_andKeepAttribution() {
        val root = File("..")
        listOf("THIRD_PARTY_NOTICES.md", "LICENSE-JARVIS-SOURCE.md").forEach { name ->
            val repoCopy = File(root, name).readText()
            val bundled = File("src/main/assets/legal/$name").readText()
            assertEquals("$name in assets/legal is out of sync with the repository root copy", repoCopy, bundled)
        }
        val notices = File("src/main/assets/legal/THIRD_PARTY_NOTICES.md").readText()
        assertTrue(notices.contains("Tech GPTx")); assertTrue(notices.contains("TAMCL v1.0")); assertTrue(notices.contains("ARPON X THE TIGER"))
        assertTrue(notices.contains("assets/nova_hud/orb.html"))
    }

    // ---------------- branding ----------------
    @Test fun strings_bothLocalesBrandedAndInParity() {
        fun names(path: String) = Regex("<string name=\"([^\"]+)\"").findAll(File(path).readText()).map { it.groupValues[1] }.toSet()
        val bn = names("src/main/res/values/strings.xml"); val en = names("src/main/res/values-en/strings.xml")
        assertEquals("keys only in bn: ${bn - en}; only in en: ${en - bn}", bn, en)
        listOf("src/main/res/values/strings.xml", "src/main/res/values-en/strings.xml").forEach { p ->
            val t = File(p).readText()
            assertTrue(t.contains("<string name=\"app_name\">NOVA AI</string>"))
            assertFalse("old product name left in $p", Regex("Nexa Ai|NEXA AI", RegexOption.IGNORE_CASE).containsMatchIn(t.replace("nexa_dashboard_title", "")))
            assertFalse(t.contains("Developer Arpon"))
        }
    }

    @Test fun branding_applicationIdAndPackageUnchanged() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("applicationId = \"com.aistudio.aiassistant.pxqtr\""))
    }
}
