package com.extreme.myhub

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable internal fun Dashboard(data: JSONObject, name: String, busy: Boolean, open: (String)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Text("WORKSPACE OVERVIEW",style=MaterialTheme.typography.labelSmall,color=HubColors.muted)
        Text("Everything, connected.",style=MaterialTheme.typography.headlineMedium)
        Text("Welcome back, $name.",style=MaterialTheme.typography.bodyMedium,color=HubColors.muted)
    }
    val connected=data.optInt("connected");val total=data.optInt("total")
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF183831),Color(0xFF142633),HubColors.surface)))) {
        Row(Modifier.fillMaxWidth().padding(22.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {HubIcon("activity",Modifier.size(17.dp),HubColors.mint);Text("Connected devices",style=MaterialTheme.typography.labelLarge,color=HubColors.mint)}
                Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(7.dp)) {Text(connected.toString(),fontSize=42.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1).sp);Text("/ $total",style=MaterialTheme.typography.titleMedium,color=HubColors.muted,modifier=Modifier.padding(bottom=7.dp))}
                Text("Your fleet at a glance",style=MaterialTheme.typography.bodySmall,color=HubColors.muted)
                TextButton(onClick={open("devices")},enabled=!busy,contentPadding=PaddingValues(0.dp)) {Text("View devices");Spacer(Modifier.width(6.dp));HubIcon("chevron",Modifier.size(14.dp))}
            }
            Box(Modifier.size(94.dp),contentAlignment=Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val width=8.dp.toPx();val inset=width/2;val arcSize=Size(size.width-width,size.height-width)
                    drawArc(HubColors.mint.copy(alpha=.12f),0f,360f,false,Offset(inset,inset),arcSize,style=Stroke(width,cap=StrokeCap.Round))
                    if(total>0&&connected>0)drawArc(HubColors.mint,-90f,360f*(connected.toFloat()/total).coerceIn(0f,1f),false,Offset(inset,inset),arcSize,style=Stroke(width,cap=StrokeCap.Round))
                }
                HubIcon("device",Modifier.size(32.dp),HubColors.mint)
            }
        }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        MetricCard("Customers",data.text("customers","0"),"people",HubColors.blue,Modifier.weight(1f),busy) {open("customers")}
        MetricCard("Total devices",total.toString(),"device",HubColors.violet,Modifier.weight(1f),busy) {open("devices")}
    }
    val attention=data.optInt("restricted")+data.optInt("recovery_holds")+data.optInt("payment_review")
    SectionHeading("Needs attention",if(attention==0) "No restricted devices or reviews right now." else "A few items are ready for your review.")
    Surface(shape=RoundedCornerShape(20.dp),color=HubColors.surface) {
        Column(Modifier.padding(horizontal=16.dp,vertical=4.dp)) {
            AttentionRow("Recovery requests",data.optInt("recovery_holds"),"shield",busy) {open("recovery-holds")}
            HorizontalDivider(color=HubColors.line.copy(alpha=.55f))
            AttentionRow("Payment reviews",data.optInt("payment_review"),"wallet",busy) {open("renewals/settings")}
            HorizontalDivider(color=HubColors.line.copy(alpha=.55f))
            AttentionRow("Restricted devices",data.optInt("restricted"),"device",busy) {open("devices")}
        }
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("${data.optInt("pending_commands")} pending commands",style=MaterialTheme.typography.bodySmall,color=HubColors.muted,modifier=Modifier.weight(1f))
        Text("${data.optInt("not_enrolled")} awaiting enrollment",style=MaterialTheme.typography.bodySmall,color=HubColors.muted,modifier=Modifier.weight(1f))
    }
    SectionHeading("Workspace tools")
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        ToolCard("App branding","Make it yours","brand",HubColors.violet,Modifier.weight(1f),busy) {open("app-brand")}
        ToolCard("Saved setups","Ready for your fleet","library",HubColors.mint,Modifier.weight(1f),busy) {open("library")}
    }
    MenuRow("Activity history","See what happened across your workspace","activity",busy,HubColors.blue) {open("audit")}
}

@Composable private fun MetricCard(label: String, value: String, icon: String, color: Color, modifier: Modifier, busy: Boolean, click: ()->Unit) {
    Surface(onClick=click,enabled=!busy,shape=RoundedCornerShape(20.dp),color=HubColors.surface,modifier=modifier) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            HubIcon(icon,Modifier.size(21.dp),color)
            Text(value,style=MaterialTheme.typography.headlineMedium)
            Text(label,style=MaterialTheme.typography.bodySmall,color=HubColors.muted)
        }
    }
}
@Composable private fun ToolCard(label: String, description: String, icon: String, color: Color, modifier: Modifier, busy: Boolean, click: ()->Unit) {
    Surface(onClick=click,enabled=!busy,shape=RoundedCornerShape(20.dp),color=HubColors.surface,modifier=modifier) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {HubIcon(icon,tint=color);Spacer(Modifier.height(4.dp));Text(label,style=MaterialTheme.typography.titleSmall);Text(description,style=MaterialTheme.typography.bodySmall,color=HubColors.muted)}
    }
}
@Composable private fun AttentionRow(label: String, count: Int, icon: String, busy: Boolean, click: ()->Unit) {
    Surface(onClick=click,enabled=!busy,color=Color.Transparent,modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            HubIcon(icon,Modifier.size(19.dp),if(count>0)HubColors.amber else HubColors.muted)
            Text(label,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(1f))
            Text(count.toString(),style=MaterialTheme.typography.labelLarge,color=if(count>0)HubColors.amber else HubColors.muted)
            HubIcon("chevron",Modifier.size(14.dp),HubColors.muted)
        }
    }
}
