package com.extreme.myhub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class Destination(val route: String, val label: String, val icon: String)
internal val destinations=listOf(Destination("overview","Home","home"),Destination("customers","Customers","people"),Destination("devices","Devices","device"),Destination("renewals/settings","Renewals","wallet"),Destination("more","More","grid"))
internal fun selectedDestination(route: String) = when(route.substringBefore('/')) {"overview"->"overview";"customers"->"customers";"devices"->"devices";"renewals"->"renewals/settings";else->"more"}

@Composable internal fun WorkspaceFrame(state: HubState, navigate: (String)->Unit, back: ()->Unit, refresh: ()->Unit, content: @Composable ColumnScope.()->Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        val wide=maxWidth>=700.dp
        Row(Modifier.fillMaxSize()) {
            if(wide) NavigationRail(containerColor=HubColors.background,modifier=Modifier.fillMaxHeight().width(96.dp),header={Spacer(Modifier.height(12.dp));IconTile("grid");Spacer(Modifier.height(24.dp))}) {
                destinations.forEach { dest -> NavigationRailItem(selected=selectedDestination(state.route)==dest.route,onClick={navigate(dest.route)},enabled=!state.busy,icon={HubIcon(dest.icon)},label={Text(dest.label)},colors=NavigationRailItemDefaults.colors(selectedIconColor=HubColors.mint,selectedTextColor=HubColors.mint,indicatorColor=HubColors.mint.copy(alpha=.10f)));Spacer(Modifier.height(10.dp)) }
            }
            Scaffold(modifier=Modifier.weight(1f),containerColor=HubColors.background,contentWindowInsets=WindowInsets(0,0,0,0),
                topBar={
                    Column {
                        Row(Modifier.fillMaxWidth().heightIn(min=72.dp).padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            if(state.canBack && state.route !in destinations.map { it.route }) IconButton(onClick=back,enabled=!state.busy,modifier=Modifier.semantics { contentDescription="Back" }) {HubIcon("back")}
                            else Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(HubColors.mint),contentAlignment=Alignment.Center) {HubIcon("grid",Modifier.size(20.dp),HubColors.background)}
                            Column(Modifier.weight(1f)) {Text(if(state.route=="overview") "My Hub" else routeTitle(state.route),style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis);Text(if(state.route=="overview") "YOUR CONTROL CENTER" else "MY HUB  /  WORKSPACE",style=MaterialTheme.typography.labelSmall,color=HubColors.muted)}
                            if(!state.route.startsWith("devices/")) IconButton(onClick=refresh,enabled=!state.busy,modifier=Modifier.semantics { contentDescription="Refresh workspace" }) {HubIcon("refresh",tint=HubColors.muted)}
                            Surface(onClick={navigate("more")},enabled=!state.busy,shape=CircleShape,color=HubColors.raised,modifier=Modifier.size(36.dp).semantics { contentDescription="Your account and workspace" }) {
                                Box(contentAlignment=Alignment.Center) {Text(state.session?.name?.take(1)?.uppercase() ?: "M",style=MaterialTheme.typography.titleSmall,color=HubColors.mint)}
                            }
                        }
                        if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp),color=HubColors.mint,trackColor=HubColors.surface)
                        else Spacer(Modifier.height(2.dp))
                    }
                },bottomBar={if(!wide) NavigationBar(containerColor=HubColors.surface,tonalElevation=0.dp,windowInsets=WindowInsets(0,0,0,0)) {
                    destinations.forEach { dest -> NavigationBarItem(selected=selectedDestination(state.route)==dest.route,onClick={navigate(dest.route)},enabled=!state.busy,
                        icon={HubIcon(dest.icon,Modifier.size(22.dp))},label={Text(dest.label,style=MaterialTheme.typography.labelSmall,maxLines=1)},
                        colors=NavigationBarItemDefaults.colors(selectedIconColor=HubColors.mint,selectedTextColor=HubColors.mint,unselectedIconColor=HubColors.muted,unselectedTextColor=HubColors.muted,indicatorColor=HubColors.mint.copy(alpha=.10f))) }
                }}
            ) { padding ->
                key(state.route) {
                    Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.TopCenter) {
                        Column(Modifier.widthIn(max=1000.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(top=12.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                            state.error?.let {MessageBanner(it,true)}
                            state.notice?.let {MessageBanner(it)}
                            content()
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun WorkspaceMenu(state: HubState, model: HubModel) {
    DetailCard("Your workspace") {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            IconTile("person");Column(Modifier.weight(1f)) {Text(state.session?.name ?: "My Hub",style=MaterialTheme.typography.titleMedium);Text("Account-connected workspace",style=MaterialTheme.typography.bodySmall,color=HubColors.muted)}
        }
        StatusPill("My Hub active")
    }
    SectionHeading("Manage your business")
    MenuRow("App branding","Your identity, artwork & app builds","brand",state.busy,HubColors.violet) {model.open("app-brand")}
    MenuRow("Trials & contacts","Trial packages and signup settings","people",state.busy,HubColors.blue) {model.open("trial-setup")}
    MenuRow("Trial contacts","Review signups and follow up","person",state.busy,HubColors.blue) {model.open("trial-contacts")}
    SectionHeading("Configure & support")
    MenuRow("Setup library","Saved setups, preferences & layouts","library",state.busy) {model.open("library")}
    MenuRow("Recovery requests","Review device connection requests","shield",state.busy,HubColors.amber) {model.open("recovery-holds")}
    MenuRow("Activity","Your workspace audit trail","activity",state.busy,HubColors.blue) {model.open("audit")}
    MenuRow("Extra Playlists","Manage your workspace add-on","plus",state.busy,HubColors.violet) {model.open("addons")}
    SectionHeading("Account")
    MenuRow("Account details","Panel credentials & account information","settings",state.busy) {model.open("account")}
    ConfirmAction("Sign out",state.busy,"Sign out of My Hub on this device?") {model.logout()}
    Text("MY HUB  ·  ${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.labelSmall,color=HubColors.muted,modifier=Modifier.padding(top=8.dp))
}
