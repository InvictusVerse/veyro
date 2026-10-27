package com.veyro.core.designsystem.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.veyro.core.designsystem.R

/**
 * The icons the Sefirah-style screens use, as vector drawables in this module's `res/drawable`
 * (`ic_sym_*`, Material Symbols copied from Sefirah-Android; see THIRD_PARTY_NOTICES.md).
 *
 * Single drawables rather than `material-icons-extended`, which costs tens of MB of dex (ADR-016).
 * Each accessor is `@Composable` because `vectorResource` reads the current resources.
 */
public object VeyroIcons {
    public val AccessTime: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_access_time)
    public val Add: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_add)
    public val ArrowBack: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_arrow_back)
    public val Battery: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_battery)
    public val BatteryCharging: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_battery_charging)
    public val BugReport: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_bug_report_fill)
    public val Call: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_call)
    public val CallFill: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_call_fill)
    public val Chat: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_chat)
    public val ChatFill: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_chat_fill)
    public val Check: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_check)
    public val Clear: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_clear)
    public val Close: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_close)
    public val Clipboard: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_content_paste)
    public val Link: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_link)
    public val Mail: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_mail)
    public val Key: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_key)
    public val OpenInNew: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_open_in_new)
    public val ContentCopy: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_content_copy)
    public val ComputerSound: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_computer_sound)
    public val ComputerSoundFill: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_computer_sound_fill)
    public val Delete: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_delete_fill)
    public val Document: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_description)
    public val Desktop: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_desktop_windows)
    public val Download: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_download)
    public val Edit: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_edit)
    public val Error: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_error)
    public val Folder: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_folder)
    public val History: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_history)
    public val HomeFill: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_home_fill)
    public val Image: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_image)
    public val Info: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_info_fill)
    public val KeyboardArrowDown: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_keyboard_arrow_down)
    public val KeyboardArrowUp: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_keyboard_arrow_up)
    public val Lock: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_lock)
    public val Mobile: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_mobile_fill)
    public val Movie: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_movie)
    public val MusicNote: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_music_note)
    public val Notifications: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_notifications)
    public val NotificationsFill: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_notifications_fill)
    public val Pause: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_pause)
    public val Phone: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_phone)
    public val PlayArrow: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_play_arrow)
    public val PlayCircle: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_play_circle)
    public val PlayCircleFill: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_play_circle_fill)
    public val QrCodeScanner: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_qr_code_scanner)
    public val RestartAlt: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_restart_alt)
    public val RocketLaunch: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_rocket_launch_fill)
    public val Search: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_search)
    public val SettingsAlert: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_settings_alert_fill)
    public val SettingsFill: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_settings_fill)
    public val Share: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_share)
    public val SkipNext: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_skip_next)
    public val SkipPrevious: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_skip_previous)
    public val Sync: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_sync)
    public val SyncDisabled: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_sync_disabled)
    public val Upload: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_upload)
    public val VolumeOff: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_volume_off_fill)
    public val VolumeUp: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_volume_up_fill)
    public val Warning: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_warning)
    public val Wifi: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_sym_wifi)
}
