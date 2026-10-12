package unipatches.compatibility

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyAppCompatibilityReceiversTest {
    @Test
    fun unsupportedInvokeDiagnosticIncludesClassMethodAndReason() {
        assertEquals(
            "Legacy compatibility: skipped receiver fix in Lexample/Receiver;->onReceive; invoke register layout is not supported (expected exactly 3 registers in 35c/3rc form).",
            receiverSkipDiagnostic(
                "Lexample/Receiver;",
                "onReceive",
                "invoke register layout is not supported (expected exactly 3 registers in 35c/3rc form).",
            ),
        )
    }

    @Test
    fun oversizedMethodDiagnosticIncludesClassMethodAndReason() {
        assertEquals(
            "Legacy compatibility: skipped receiver fix in Lexample/Receiver;->onReceive; temporary register range exceeds the const/16 encoding limit.",
            receiverSkipDiagnostic(
                "Lexample/Receiver;",
                "onReceive",
                "temporary register range exceeds the const/16 encoding limit.",
            ),
        )
    }

    @Test
    fun highRegisterCallUsesCompatHelperRangeInvokeWithoutInlineBranch() {
        val block = requireNotNull(appWideReceiverCompatBlock(listOf(18, 19, 20), RECEIVER_EXPORTED, scratchBase = 30))

        assertTrue(block.contains("move-object/from16 v30, v18"))
        assertTrue(block.contains("const/16 v33, $RECEIVER_EXPORTED"))
        assertTrue(block.contains("invoke-static/range {v30 .. v33}, Lunipatch/compatcore/LegacyCompatRuntime;->registerReceiverCompat"))
        assertFalse(block.contains("if-lt"))
    }

    @Test
    fun receiverCompatHelperRejectsUnencodableTemporaryRegisters() {
        assertNull(appWideReceiverCompatBlock(listOf(18, 19, 20), RECEIVER_EXPORTED, scratchBase = 253))
    }

    @Test
    fun appScopedActionsUsePrivateFlag() {
        assertEquals(
            RECEIVER_NOT_EXPORTED,
            receiverFlagsForActions(setOf("com.example.legacy.action.STATE"), "com.example.legacy"),
        )
    }

    @Test
    fun systemAndKnownExternalActionsUseExportedFlag() {
        assertEquals(RECEIVER_EXPORTED, receiverFlagsForActions(setOf("android.intent.action.SCREEN_ON"), "com.example.legacy"))
        assertEquals(RECEIVER_EXPORTED, receiverFlagsForActions(setOf("com.amazon.device.ads.ACTION"), "com.example.legacy"))
    }

    @Test
    fun vendorNamespaceCollisionsFailClosed() {
        assertNull(receiverFlagsForActions(setOf("com.amazon.internal.refresh"), "com.example.legacy"))
        assertNull(receiverFlagsForActions(setOf("com.amazon.device.ads.internal.refresh"), "com.example.legacy"))
        assertNull(receiverFlagsForActions(setOf("com.amazon.inapp.purchasing.internal.refresh"), "com.example.legacy"))
        assertNull(receiverFlagsForActions(setOf("com.android.vending.internal.refresh"), "com.example.legacy"))
        assertNull(receiverFlagsForActions(setOf("com.android.vending.billing.internal.refresh"), "com.example.legacy"))
        assertNull(receiverFlagsForActions(setOf("com.google.android.c2dm.intent.internal.refresh"), "com.example.legacy"))
        assertNull(receiverFlagsForActions(setOf("com.android.internal.refresh"), "com.example.legacy"))
        assertNull(receiverFlagsForActions(setOf("com.google.android.internal.refresh"), "com.example.legacy"))
    }

    @Test
    fun mixedAppAndExternalActionsPreferExportedFlag() {
        assertEquals(
            RECEIVER_EXPORTED,
            receiverFlagsForActions(
                setOf("com.example.legacy.action.STATE", "android.intent.action.SCREEN_ON"),
                "com.example.legacy",
            ),
        )
    }

    @Test
    fun resolvesOnlyActionsFromTheFilterPassedToEachCall() {
        val stringConstructor = ImmutableMethodReference(
            "Landroid/content/IntentFilter;",
            "<init>",
            listOf("Ljava/lang/String;"),
            "V",
        )
        val instructions = listOf(
            BuilderInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference("Landroid/content/IntentFilter;")),
            BuilderInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference("com.example.app.ACTION_PRIVATE")),
            BuilderInstruction35c(Opcode.INVOKE_DIRECT, 2, 0, 1, 0, 0, 0, stringConstructor),
            BuilderInstruction21c(Opcode.NEW_INSTANCE, 4, ImmutableTypeReference("Landroid/content/IntentFilter;")),
            BuilderInstruction21c(Opcode.CONST_STRING, 5, ImmutableStringReference("android.intent.action.SCREEN_ON")),
            BuilderInstruction35c(Opcode.INVOKE_DIRECT, 2, 4, 5, 0, 0, 0, stringConstructor),
            BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL,
                3,
                2,
                3,
                4,
                0,
                0,
                ImmutableMethodReference(
                    "Landroid/content/Context;",
                    "registerReceiver",
                    listOf("Landroid/content/BroadcastReceiver;", "Landroid/content/IntentFilter;"),
                    "Landroid/content/Intent;",
                ),
            ),
        )


        assertEquals(setOf("android.intent.action.SCREEN_ON"), resolvedReceiverFilterActions(instructions, 6, 4))
    }


    @Test
    fun unresolvedFilterActionsAreSkipped() {
        val noArgConstructor = ImmutableMethodReference("Landroid/content/IntentFilter;", "<init>", emptyList(), "V")
        val instructions = listOf(
            BuilderInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference("Landroid/content/IntentFilter;")),
            BuilderInstruction35c(Opcode.INVOKE_DIRECT, 1, 0, 0, 0, 0, 0, noArgConstructor),
            BuilderInstruction10x(Opcode.NOP),
        )


        assertNull(resolvedReceiverFilterActions(instructions, 2, 0))
    }

    @Test
    fun playhavenContentEventAndPurchaseCallbackAreAppPrivate() {
        assertEquals(
            RECEIVER_NOT_EXPORTED,
            receiverFlagsForActions(setOf("com.playhaven.src.publishersdk.content.PHContentViewEvent"), "com.example.legacy"),
        )
        assertEquals(
            RECEIVER_NOT_EXPORTED,
            receiverFlagsForActions(setOf("com.playhaven.src.publishersdk.content.PHContentViewPurchaseCallback"), "com.example.legacy"),
        )
    }

    @Test
    fun exactPlayhavenPurchaseReceiverFingerprintResolvesOnlyItsKnownPrefix() {
        val prefix = "com.playhaven.src.publishersdk.content.PHContentViewPurchaseCallback"
        val stringConstructor = ImmutableMethodReference(
            "Landroid/content/IntentFilter;",
            "<init>",
            listOf("Ljava/lang/String;"),
            "V",
        )
        val instructions = listOf(
            BuilderInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(prefix)),
            BuilderInstruction21c(Opcode.NEW_INSTANCE, 2, ImmutableTypeReference("Landroid/content/IntentFilter;")),
            BuilderInstruction35c(Opcode.INVOKE_DIRECT, 2, 2, 1, 0, 0, 0, stringConstructor),
        )

        val actions = knownInternalPlayhavenReceiverActions(
            "Lcom/playhaven/src/publishersdk/content/PHContentView;",
            "registerPurchaseReceiver",
            instructions,
        )
        assertEquals(setOf(prefix), actions)
        assertNull(knownInternalPlayhavenReceiverActions("Lexample/Other;", "registerPurchaseReceiver", instructions))
    }

    @Test
    fun uncertainVendorActionsRemainFailClosed() {
        assertNull(receiverFlagsForActions(setOf("OPT_IN_STATUS_BROADCAST"), "com.example.legacy"))
        assertNull(receiverFlagsForActions(setOf("com.unclassified.sdk.ACTION"), "com.example.legacy"))
        assertNull(receiverFlagsForActions(setOf("com.example.legacy.action.INTERNAL"), null))
    }
}
