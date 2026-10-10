package com.freebuff.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.ui.theme.LocalTokens

@Composable
fun WelcomeScreen(viewModel: WelcomeViewModel = hiltViewModel()) {
    val t = LocalTokens.current
    // 读屏描述必须在组合期取好:Modifier.semantics 的 lambda 不是 @Composable
    val logoCd = stringResource(R.string.welcome_logo_cd)
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(90.dp))
        Box(
            // 字形 ">_" 对读屏没有意义:用 clearAndSetSemantics 换成「切换主题」
            modifier = Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(t.surface)
                .clickable { viewModel.cycleTheme() }
                .clearAndSetSemantics { contentDescription = logoCd },
            contentAlignment = Alignment.Center,
        ) {
            Text(">_", color = t.accent, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.welcome_title_lead), color = t.text, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.welcome_brand), color = t.accent, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.welcome_subtitle),
            color = t.text2, fontSize = 13.5.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        FeatureRow(stringResource(R.string.welcome_feature_models))
        FeatureRow(stringResource(R.string.welcome_feature_agent))
        FeatureRow(stringResource(R.string.welcome_feature_local))
        Spacer(Modifier.weight(1f))
        PrimaryBtn(stringResource(R.string.welcome_sign_in)) { viewModel.enter(signedIn = true) }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.welcome_guest), color = t.text3, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { viewModel.enter(signedIn = false) }.padding(12.dp))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.welcome_guest_note), color = t.text3, fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun FeatureRow(text: String) {
    val t = LocalTokens.current
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("✦", color = t.accent, fontSize = 13.sp)
        Spacer(Modifier.width(10.dp))
        Text(text, color = t.text2, fontSize = 13.sp)
    }
}

@Composable
private fun PrimaryBtn(text: String, onClick: () -> Unit) {
    val t = LocalTokens.current
    Text(text, color = t.accentInk, fontSize = 15.5.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.accent)
            .clickable { onClick() }.padding(vertical = 15.dp),
        textAlign = TextAlign.Center)
}
