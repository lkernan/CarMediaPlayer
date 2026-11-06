package com.saicmotor.common

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/**
 * The command the SAIC launcher and the stock media app send each other as
 * the `command` extra of a startService intent (source switching, "is music
 * available", "this is the active source").
 *
 * Must keep this exact class name and parcel layout: the launcher puts its
 * own copy of the class in the intent, and Android rebuilds it in our
 * process by name.
 */
class CommandParcelable() : Parcelable {

    var event:        Int    = -1
    var targetPackage: String = ""
    var targetType:   Int    = -1
    var targetState:  Int    = -1
    var param:        Bundle = Bundle()

    constructor(event: Int, targetPackage: String, targetType: Int, targetState: Int) : this() {
        this.event         = event
        this.targetPackage = targetPackage
        this.targetType    = targetType
        this.targetState   = targetState
    }

    private constructor(parcel: Parcel) : this() {
        event         = parcel.readInt()
        targetPackage = parcel.readString() ?: ""
        targetType    = parcel.readInt()
        targetState   = parcel.readInt()
        param         = parcel.readBundle(Bundle::class.java.classLoader) ?: Bundle()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(event)
        parcel.writeString(targetPackage)
        parcel.writeInt(targetType)
        parcel.writeInt(targetState)
        parcel.writeBundle(param)
    }

    override fun describeContents() = 0

    /** Sends this to a service in another SAIC app (explicit component). */
    fun sendTo(context: Context, pkg: String, service: String) {
        context.startService(Intent()
            .setComponent(ComponentName(pkg, service))
            .setPackage(pkg)
            .putExtra(COMMAND_KEY, this))
    }

    override fun toString() =
        "CommandParcelable{event=$event, package='$targetPackage', type=$targetType, state=$targetState}"

    companion object {
        const val COMMAND_KEY = "command"

        const val LAUNCHER_PACKAGE = "com.saicmotor.launcher"
        const val LAUNCHER_SERVICE = "com.saicmotor.launcher.model.LauncherService"

        // Events (com.saicmotor.common.ModeConstants in the firmware)
        /** Launcher → us: switch to this source and play. */
        const val SWITCH_SOURCE_ONLY        = 32769
        /** Launcher → us: switch to this source, playing (restoring the last mode at start-up). */
        const val SWITCH_SOURCE_ONLY_PLAY   = 32773
        /** Launcher → us: switch to this source, paused (restoring the last mode at start-up). */
        const val SWITCH_SOURCE_ONLY_PAUSE  = 32774
        /** Both ways: which media sources can play (launcher asks, we answer). */
        const val UPDATE_SOURCE_AVAILABLE   = 16417

        // Media source types (targetType)
        const val TYPE_USB1   = 0
        const val TYPE_USB2   = 1
        const val TYPE_BT     = 2
        const val TYPE_ONLINE = 3

        // Availability (targetState), and where the stock app also records it
        const val ENABLE  = 1
        const val DISABLE = 0
        const val SETTING_USB1   = "usb1AvailableStatus"
        const val SETTING_USB2   = "usb2AvailableStatus"
        const val SETTING_BT     = "btAvailableStatus"
        const val SETTING_ONLINE = "onlineAvailableStatus"

        @JvmField
        val CREATOR = object : Parcelable.Creator<CommandParcelable> {
            override fun createFromParcel(parcel: Parcel) = CommandParcelable(parcel)
            override fun newArray(size: Int) = arrayOfNulls<CommandParcelable>(size)
        }
    }
}
