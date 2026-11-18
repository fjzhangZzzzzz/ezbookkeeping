package top.fjzhang233.books

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.edit
import androidx.core.view.WindowCompat

internal enum class ThemeMode(val key:String,val label:String){SYSTEM("system","跟随系统"),LIGHT("light","浅色"),DARK("dark","深色")}
internal fun decodeThemeMode(value:String?):ThemeMode=ThemeMode.entries.firstOrNull{it.key==value}?:ThemeMode.SYSTEM
internal data class LedgerPalette(val dark:Boolean,val background:Color,val surface:Color,val field:Color,val text:Color,val muted:Color,val outline:Color,val divider:Color,val selection:Color,val accent:Color,val expense:Color,val income:Color,val heatExpenseLow:Color,val heatExpenseHigh:Color,val heatIncomeLow:Color,val heatIncomeHigh:Color){val onAccent:Color get()=if(dark)Color.Black else Color.White;val controlSurface:Color get()=if(dark)surface else this.field}
internal fun ledgerPalette(dark:Boolean)=if(dark)LedgerPalette(true,Color.Black,Color(0xFF1A1A1A),Color(0xFF242424),Color(0xFFBDBDBD),Color(0xFF999999),Color(0xFF666666),Color(0xFF303030),Color(0xFF1F3044),Color(0xFF55A1FF),Color(0xFFF06B73),Color(0xFF25B87A),Color(0xFF291E1F),Color(0xFF382324),Color(0xFF1B2521),Color(0xFF1B3128)) else LedgerPalette(false,Color(0xFFF4F5F7),Color.White,Color(0xFFE9EDF1),Color(0xFF1D232B),Color(0xFF59636F),Color(0xFF77818D),Color(0xFFDCE1E7),Color(0xFFDFEDFD),Color(0xFF0866C8),Color(0xFFB82431),Color(0xFF14734C),Color(0xFFFFF0F1),Color(0xFFFFE0E3),Color(0xFFEDF7F1),Color(0xFFD9EFDF))
private val LocalLedgerPalette=staticCompositionLocalOf{ledgerPalette(true)}
internal val ledgerColors:LedgerPalette @Composable get()=LocalLedgerPalette.current
private fun Context.themePreferences()=applicationContext.getSharedPreferences("appearance",Context.MODE_PRIVATE)
internal fun saveThemeMode(context:Context,mode:ThemeMode):Boolean=runCatching{context.themePreferences().edit{putString("theme",mode.key)}}.isSuccess
@Composable internal fun rememberThemeMode():ThemeMode{
    val prefs=LocalContext.current.themePreferences();var mode by remember(prefs){mutableStateOf(decodeThemeMode(prefs.getString("theme",null)))}
    DisposableEffect(prefs){val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,key->if(key=="theme")mode=decodeThemeMode(prefs.getString("theme",null))};prefs.registerOnSharedPreferenceChangeListener(listener);onDispose{prefs.unregisterOnSharedPreferenceChangeListener(listener)}}
    return mode
}
/** A local appearance preference, independent of credentials and accounting state. */
@Composable fun FamilyTheme(content:@Composable ()->Unit){
    val mode=rememberThemeMode();val systemDark=isSystemInDarkTheme();val dark=when(mode){ThemeMode.SYSTEM->systemDark;ThemeMode.DARK->true;ThemeMode.LIGHT->false};val p=ledgerPalette(dark)
    val base=if(dark)darkColorScheme()else lightColorScheme()
    val scheme=base.copy(primary=p.accent,onPrimary=p.onAccent,primaryContainer=p.selection,onPrimaryContainer=p.accent,secondary=p.text,onSecondary=p.surface,secondaryContainer=p.field,onSecondaryContainer=p.text,tertiary=p.text,onTertiary=p.surface,tertiaryContainer=p.field,onTertiaryContainer=p.text,background=p.background,onBackground=p.text,surface=p.surface,onSurface=p.text,onSurfaceVariant=p.muted,surfaceVariant=p.field,surfaceContainerLowest=p.background,surfaceContainerLow=p.surface,surfaceContainer=p.surface,surfaceContainerHigh=p.surface,surfaceContainerHighest=p.field,outline=p.outline,outlineVariant=p.divider,error=p.expense,onError=p.onAccent,errorContainer=if(dark)Color(0xFF5A2028)else Color(0xFFFFDADD),onErrorContainer=if(dark)Color(0xFFFFDADD)else Color(0xFF700019))
    val view=LocalView.current;val context=LocalContext.current
    SideEffect{
        val window=(view.parent as? DialogWindowProvider)?.window?:run{var current=context;while(current is android.content.ContextWrapper&&current !is Activity)current=current.baseContext;(current as? Activity)?.window}
        window?.let{WindowCompat.getInsetsController(it,view).apply{isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark};if(view.parent !is DialogWindowProvider)it.setBackgroundDrawable(ColorDrawable(p.background.toArgb()))}
    }
    CompositionLocalProvider(LocalLedgerPalette provides p){MaterialTheme(colorScheme=scheme,content=content)}
}
