package com.extreme.myhub

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object HubColors {
    val background = Color(0xFF0B111B)
    val surface = Color(0xFF141E2C)
    val raised = Color(0xFF1C293A)
    val mint = Color(0xFF72E6BF)
    val blue = Color(0xFF88BCFF)
    val violet = Color(0xFFB7A5F9)
    val amber = Color(0xFFF0C47B)
    val text = Color(0xFFF1F5FA)
    val muted = Color(0xFF98A9BC)
    val line = Color(0xFF293749)
}

@Composable internal fun HubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = HubColors.mint, onPrimary = Color(0xFF082A23), primaryContainer = Color(0xFF203F39), onPrimaryContainer = HubColors.mint,
            secondary = HubColors.blue, secondaryContainer = HubColors.raised, onSecondaryContainer = HubColors.text,
            background = HubColors.background, onBackground = HubColors.text, surface = HubColors.surface, onSurface = HubColors.text,
            surfaceVariant = HubColors.raised, onSurfaceVariant = HubColors.muted, outline = HubColors.line, outlineVariant = HubColors.line,
            error = Color(0xFFFFA5AA), errorContainer = Color(0xFF452831)),
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
            headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
            headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
            titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
            labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)),
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content
    )
}

/** Small, consistent line icons drawn locally; no font or network dependency. */
@Composable internal fun HubIcon(name: String, modifier: Modifier = Modifier.size(22.dp), tint: Color = LocalContentColor.current) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        val stroke = Stroke(1.7f * unit, cap = StrokeCap.Round)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(tint, point(x,y), point(x2,y2), stroke.width, StrokeCap.Round)
        fun box(x: Float, y: Float, w: Float, h: Float, r: Float = 2f) = drawRoundRect(tint, point(x,y), Size(w*unit,h*unit), CornerRadius(r*unit), style=stroke)
        fun circle(x: Float, y: Float, r: Float) = drawCircle(tint,r*unit,point(x,y),style=stroke)
        when (name) {
            "home" -> { val p=Path().apply { moveTo(3*unit,11*unit);lineTo(12*unit,3*unit);lineTo(21*unit,11*unit) };drawPath(p,tint,style=stroke);line(5f,10f,5f,21f);line(5f,21f,19f,21f);line(19f,21f,19f,10f);box(9f,14f,6f,7f,0.5f) }
            "people", "person" -> { circle(10f,7f,3f); val p=Path().apply { moveTo(3*unit,21*unit);cubicTo(3*unit,11*unit,17*unit,11*unit,17*unit,21*unit) };drawPath(p,tint,style=stroke); if(name=="people"){line(18f,4f,19f,10f);line(20f,14f,22f,20f)} }
            "device" -> {box(3f,4f,18f,13f);line(8f,21f,16f,21f);line(12f,17f,12f,21f)}
            "wallet" -> {box(3f,5f,18f,15f);box(14f,10f,8f,6f,1f);circle(17f,13f,0.3f);line(5f,5f,17f,2f)}
            "grid", "library" -> {box(3f,3f,7f,7f,1.5f);box(14f,3f,7f,7f,1.5f);box(3f,14f,7f,7f,1.5f);box(14f,14f,7f,7f,1.5f)}
            "chevron" -> {line(9f,6f,15f,12f);line(15f,12f,9f,18f)}
            "back" -> {line(14f,5f,7f,12f);line(7f,12f,14f,19f)}
            "down" -> {line(6f,9f,12f,15f);line(12f,15f,18f,9f)}
            "search" -> {circle(10f,10f,6f);line(15f,15f,21f,21f)}
            "plus" -> {line(12f,5f,12f,19f);line(5f,12f,19f,12f)}
            "check" -> {line(5f,12f,10f,17f);line(10f,17f,20f,6f)}
            "close" -> {line(6f,6f,18f,18f);line(6f,18f,18f,6f)}
            "refresh" -> {drawArc(tint,40f,280f,false,point(4f,4f),Size(16*unit,16*unit),style=stroke);line(20f,3f,20f,9f);line(20f,9f,14f,9f)}
            "activity" -> {line(2f,12f,6f,12f);line(6f,12f,9f,5f);line(9f,5f,14f,20f);line(14f,20f,17f,12f);line(17f,12f,22f,12f)}
            "lock" -> {box(5f,10f,14f,11f);drawArc(tint,180f,180f,false,point(8f,2f),Size(8*unit,14*unit),style=stroke);line(12f,14f,12f,17f)}
            "eye" -> {val p=Path().apply {moveTo(2*unit,12*unit);quadraticBezierTo(12*unit,0f,22*unit,12*unit);quadraticBezierTo(12*unit,24*unit,2*unit,12*unit)};drawPath(p,tint,style=stroke);circle(12f,12f,3f)}
            "filter", "settings" -> {for(i in 0..2){val y=5f+i*7;line(3f,y,21f,y);drawCircle(HubColors.surface,2.5f*unit,point(if(i==1)8f else 16f,y));circle(if(i==1)8f else 16f,y,2.5f)}}
            "clock" -> {circle(12f,12f,9f);line(12f,6f,12f,12f);line(12f,12f,16f,14f)}
            "brand" -> {box(4f,4f,16f,16f,5f);circle(12f,12f,4f);line(17f,3f,21f,7f)}
            "shield" -> {val p=Path().apply{moveTo(12*unit,2*unit);lineTo(20*unit,6*unit);lineTo(19*unit,15*unit);quadraticBezierTo(16*unit,20*unit,12*unit,22*unit);quadraticBezierTo(8*unit,20*unit,5*unit,15*unit);lineTo(4*unit,6*unit);close()};drawPath(p,tint,style=stroke);line(8f,12f,11f,15f);line(11f,15f,16f,9f)}
            "logout" -> {line(10f,3f,4f,3f);line(4f,3f,4f,21f);line(4f,21f,10f,21f);line(10f,12f,22f,12f);line(17f,7f,22f,12f);line(22f,12f,17f,17f)}
            else -> {circle(12f,12f,9f);line(12f,7f,12f,13f);circle(12f,17f,0.4f)}
        }
    }
}

@Composable internal fun IconTile(icon: String, color: Color = HubColors.mint) {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha=.10f)), contentAlignment=Alignment.Center) { HubIcon(icon,tint=color) }
}

@Composable internal fun StatusPill(text: String, color: Color = HubColors.mint) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha=.10f)).padding(horizontal=9.dp,vertical=5.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(5.dp).background(color,CircleShape));Text(text,style=MaterialTheme.typography.labelSmall,color=color)
    }
}

@Composable internal fun SectionHeading(text: String, detail: String? = null) {
    Column(Modifier.padding(top=6.dp,bottom=2.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) { Text(text,style=MaterialTheme.typography.titleMedium);if(detail!=null)Text(detail,style=MaterialTheme.typography.bodySmall,color=HubColors.muted) }
}

@Composable internal fun MenuRow(name: String, description: String, icon: String, busy: Boolean = false, color: Color = HubColors.mint, run: () -> Unit) {
    Surface(onClick=run,enabled=!busy,shape=RoundedCornerShape(18.dp),color=HubColors.surface,modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            IconTile(icon,color)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {Text(name,style=MaterialTheme.typography.titleSmall);Text(description,style=MaterialTheme.typography.bodySmall,color=HubColors.muted)}
            HubIcon("chevron",Modifier.size(17.dp),HubColors.muted)
        }
    }
}

@Composable internal fun MessageBanner(message: String, error: Boolean = false) {
    val color=if(error) MaterialTheme.colorScheme.error else HubColors.mint
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha=.10f)).padding(14.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        HubIcon(if(error) "alert" else "check",Modifier.size(18.dp),color);Text(message,style=MaterialTheme.typography.bodySmall,color=color,modifier=Modifier.weight(1f))
    }
}

@Composable internal fun EmptyState(title: String, description: String, icon: String = "search") {
    Column(Modifier.fillMaxWidth().padding(vertical=32.dp,horizontal=16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        IconTile(icon,HubColors.muted);Text(title,style=MaterialTheme.typography.titleMedium);Text(description,style=MaterialTheme.typography.bodyMedium,color=HubColors.muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    }
}

internal fun routeTitle(route: String): String = when(route.substringBefore('/')) {
    "overview" -> "Overview"; "more" -> "Workspace"; "renewals" -> "Renewals"; "app-brand" -> "App branding"; "trial-setup" -> "App trials"; "addons" -> "Extra Playlists"; "library" -> "Library"; "device-profiles" -> "Saved setups"; "device-defaults" -> "Automatic setup"; "device-setups" -> "Setup activity"; "preference-profiles" -> "Preference profiles"; "category-layout-templates" -> "Category layouts"; "settings-presets" -> "Sorting presets"; "audit" -> "Activity"; else -> title(route.substringBefore('/'))
}
