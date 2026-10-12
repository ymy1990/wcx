package com.Johnny.wcx.features.items.secret_friend

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.features.items.secret_friend.advanced.installVoipHooks
import org.luckypray.dexkit.query.matchers.MethodMatcher

@Feature(name = "自动拒绝音视频通话", categories = ["密友功能"],
    description = "隐藏密友时自动拒绝其语音、视频或群通话；临时显示期间正常接收")
object AutoRejectVoip : SwitchFeature() {
    override val defaultEnabled: Boolean
        get() = WePrefs.getBoolOrDef("hide_auto_reject", false)
}

/** Keep the existing generic contact feature independent from the secret-friend list. */
@Feature(name = "密友通话运行支持", categories = [], description = "密友音视频通知隐藏和自动拒接共用的运行支持")
object SecretFriendVoipHooks : ApiFeature(), IResolveDex {
    override fun onEnable() = installVoipHooks()
    internal fun isHiddenNow(wxId: String): Boolean =
        (MuteSecretFriend.isEnabled || AutoRejectVoip.isEnabled) &&
            !SecretFriendState.isTemporarilyShown() && SecretFriendState.isSecret(wxId)
    internal val autoRejectVoipEnabled: Boolean get() = AutoRejectVoip.isEnabled

    // ── VoIPMP / ILink (the stack that actually runs on 8.0.7x) ──────────────────────────────
    // See hidecontacts/HideContactsVoip.kt for how these fit together.

    /** `ZIDL_ibmKH7hbMB.ZIDL_FBV(long, int, int, long, long, byte[] username, byte[][], boolean)` */
    internal val methodVoipMpLaunchIncomingCard by dexMethod {
        matcher {
            // 8.0.76 changed from "launchInComingCardAsync: " to "[volume report] launchInComingCardAsync: "
            usingStrings("MicroMsg.VoIPMP.CoreV2", "launchInComingCardAsync: ")
        }
    }

    /**
     * `mp5.q2.qa(Context, int, is4.r, long, long, String username, ArrayList, boolean)` — the
     * banner/notification/ringtone dispatcher. q2 declares exactly one 8-parameter method, so the
     * class anchor plus the parameter count is unambiguous.
     */
    internal val methodVoipMpLaunchBanner by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.VoIPMP.Launcher", "closeReceiverBanner")
            }
            paramCount = 8
            returnType("void")
        }
    }

    /** `mp5.q2.Qa()` — "rejectByShortCut", the entry WeChat's own quick-reject uses. */
    internal val methodVoipMpReject by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.VoIPMP.CoreV2", "rejectByShortCut")
            paramCount = 0
            returnType("void")
        }
    }

    /**
     * `nq5.e.a(String username, boolean videoCall, boolean outCall, long, boolean)` — the incoming
     * ringtone. NB: this is NOT the old `MicroMsg.RingPlayer` / "playSound, type: ..." match, which
     * resolved to the call-ENDED tone and therefore never silenced anything.
     */
    internal val methodVoipMpStartRing by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.VoIPMPRingtoneController", "startRing() called with: username = ")
        }
    }

    /** `xp5.b.d(String username, boolean, boolean, boolean)` — starts the VoIP foreground service. */
    internal val methodVoipMpStartFgs by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.VoIPMPVoIPNotificationHelper", "startFGS isBindVoIPForegroundService ")
        }
    }

    /** `mp5.q2.Ii(String toUser, ...)` — VoIPMP call-record insertion (未接听 / 已取消 / duration). */
    internal val methodVoipMpInsertMsg by dexMethod {
        matcher {
            paramTypes(
                "java.lang.String",
                "boolean",
                "int",
                "long",
                "long",
                "long",
                "int",
            )
            returnType("void")
            anyOf(
                MethodMatcher().apply {
                    usingEqStrings(
                        "MicroMsg.VoIPMP.Launcher",
                        "insertMsg() called with: toUser = ",
                    )
                },
                MethodMatcher().apply {
                    declaredClass {
                        usingEqStrings("MicroMsg.VoIPMP.Launcher", "closeReceiverBanner")
                    }
                },
            )
        }
    }

    // ── multitalk (群通话), used when the VoIPMP multitalk experiment is off ───────────────────

    /** `v0.G(MultiTalkGroup)` — MultiTalkManager.onInviteMultiTalk. */
    internal val methodMultiTalkOnInvite by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(
                "MicroMsg.MT.MultiTalkManager",
                "onInviteMultiTalk All Var Value:\n isMute: %b isHandsFree: %b isCameraFace: %b multiTalkStatus: %s groupIsNull: %b",
            )
        }
    }

    /**
     * `v0.g(isReject, isMissCall, isPhoneCall, isNetworkError, boolean, boolean)` —
     * exitCurrentMultiTalk. Declared on the same `v0` (MultiTalkManager) as [methodMultiTalkOnInvite],
     * so the invite hook's `thisObject` is the receiver to invoke this on — no separate singleton
     * lookup needed.
     */
    internal val methodExitMultiTalk by dexMethod(allowFailure = true) {
        matcher {
            usingStrings(
                "exitCurrentMultiTalk: isReject %b isMissCall %b isPhoneCall %b isNetworkError %b",
            )
        }
    }

    // ── legacy v2protocal stack (only reached when the peer downgrades) ───────────────────────

    /** `nr4.y.x(...)` — the incoming float card. Shared by both stacks, so live on 8.0.76 as well. */
    internal val methodVoipShowFloatingCard by dexMethod {
        matcher {
            usingEqStrings(".ui.voip.VoipFloatView")
            paramCount = 8
        }
    }

    /**
     * `nr4.y.z(Context, String toUser)` — AnimatedVoipBaseFloatCardManager.showFinishCard, the
     * "已拒绝通话" banner shown *after* a rejection. Distinct from [methodVoipShowFloatingCard]
     * (the incoming card) even though both live on `nr4.y`, so suppressing the incoming card does
     * not cover it.
     *
     * The bare "showFinishCard" string constant occurs only in this method (the lambda classes
     * carry longer `...$showFinishCard$3$2$...` constants, which `usingEqStrings` will not match).
     */
    internal val methodVoipShowFinishCard by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("showFinishCard", "(Landroid/content/Context;Ljava/lang/String;)V")
            paramCount = 2
        }
    }
    internal val methodVoipAcceptIncomingCall by dexMethod {
        searchPackages("com.tencent.mm.plugin.voip")
        matcher {
            usingEqStrings("MicroMsg.VoipIncomingCallManager", "acceptIncomingCal, roomInfo:")
        }
    }
    internal val methodVoipStartAcceptVoip by dexMethod {
        searchPackages("com.tencent.mm.plugin.voip")
        matcher {
            usingEqStrings("MicroMsg.VoipIncomingCallManager", "startAcceptVoIP, roomInfo:")
        }
    }
    internal val methodVoipServiceExSetInviteContent by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.Voip.VoipServiceEx", "Failed to setInviteContent during calling, status =")
        }
    }
    internal val methodVoipServiceExReject by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.Voip.VoipServiceEx", "Failed to reject with calling, status =")
        }
    }

    /** `j0.j(String content, a65.j4 addMsg)` — server-pushed `<voipmsg>` bubble (msg type 50). */
    internal val methodVoipBubbleHandle by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.VoIPBubbleHelper", "handlerBubbleMsg: parse bubble info error")
        }
    }

    /**
     * `b2.d(String talker, String, int, int, String, boolean, k0, f16.l)` — legacy call-record
     * insertion.
     *
     * NB: do NOT match on "insertMsg() called with: voipInfo = " — those strings live in the
     * synthetic Runnable `b2$$a.run()`, which takes ZERO parameters, so the previous matcher made
     * `args[0]` throw on every legacy call record. The callagain URL is unique to b2 itself, and
     * `d` is the only 8-parameter method it declares.
     */
    internal val methodVoipLegacyInsertMsg by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.VoipPluginManager", "weixin://voip/callagain/?username=")
            }
            paramCount = 8
            returnType("void")
        }
    }

}
