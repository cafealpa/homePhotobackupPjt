package com.chochocho.homephotoclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.chochocho.homephotoclient.backup.BackupEngine
import com.chochocho.homephotoclient.data.SettingsRepository
import com.chochocho.homephotoclient.ui.BackupScreen
import com.chochocho.homephotoclient.ui.PeopleScreen
import com.chochocho.homephotoclient.ui.SettingsScreen
import com.chochocho.homephotoclient.ui.TimelineScreen
import com.chochocho.homephotoclient.ui.theme.HomePhotoTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35+ 강제 edge-to-edge에서 상태바 아이콘 색을 라이트/다크 테마에 맞게 지정
        // (이 호출 없이는 흰 배경 위에 흰색 아이콘이 얹혀 보이지 않는다)
        enableEdgeToEdge()
        val settingsRepository = SettingsRepository(applicationContext)
        val backupEngine = BackupEngine.get(applicationContext)

        setContent {
            HomePhotoTheme {
                var selectedTab by remember { mutableIntStateOf(0) }
                val tabs = listOf("사진", "인물", "백업", "설정")

                Scaffold { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        TabRow(selectedTabIndex = selectedTab) {
                            tabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = selectedTab == index,
                                    onClick = { selectedTab = index },
                                    text = { Text(title) },
                                )
                            }
                        }
                        when (selectedTab) {
                            0 -> TimelineScreen(repository = settingsRepository)
                            1 -> PeopleScreen(repository = settingsRepository)
                            2 -> BackupScreen(engine = backupEngine)
                            else -> SettingsScreen(repository = settingsRepository)
                        }
                    }
                }
            }
        }
    }
}
