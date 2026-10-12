package com.plugin

import com.plugin.compatibility.SocialProfilePictures
import com.plugin.compatibility.FrequentAccount
import com.plugin.compatibility.FrequentAccountStore
import com.plugin.compatibility.SocialPostMetadata
import com.plugin.compatibility.SocialPostMetadataPreferences
import com.plugin.compatibility.SocialPostMetadataStore
import com.plugin.compatibility.WallpaperHelper
import org.junit.Assert.*
import org.junit.Test

/** 자주 방문하는 계정, 게시글 정보 및 배경화면/인스타그램 퀵 기능 단위 테스트 */
class FrequentAccountsAndPostInfoTest {

    /** 공개 응답 본문을 보존하고 잘못된 프로필 주소·게시글 식별자를 거부합니다. */
    @Test fun instagramMetadataIsPureAndKeepsPublicCaption() {
        val post = kotlinx.serialization.json.Json.parseToJsonElement("""{"code":"ABC","owner":{"username":"review","profile_pic_url":"https://evil.invalid/a.jpg"},"edge_media_to_caption":{"edges":[{"node":{"text":"설명 #테스트 #테스트"}}]}}""") as kotlinx.serialization.json.JsonObject
        val metadata = checkNotNull(SocialPostMetadataStore.instagramMetadata(post))
        assertEquals("설명 #테스트 #테스트", metadata.text)
        assertEquals(listOf("#테스트"), metadata.hashtags)
        assertEquals("", metadata.authorProfilePic)
        assertNull(SocialPostMetadataStore.instagramMetadata(kotlinx.serialization.json.JsonObject(post + ("code" to kotlinx.serialization.json.JsonPrimitive("../bad")))))
    }

    /** 계정이 다른 이미지·외부 호스트를 제외하고 같은 계정의 고해상도 사진만 가져옵니다. */
    @Test fun profilePicturesUseVerifiedAccountData() {
        val html = """<script>{"users":[{"username":"other","profile_pic_url":"https://s.cdninstagram.com/other.jpg"},{"username":"review","profile_pic_url":"https://s.cdninstagram.com/low.jpg","profile_pic_url_hd":"https://s.cdninstagram.com/high.jpg"}]}</script>"""
        assertEquals("https://s.cdninstagram.com/high.jpg", SocialProfilePictures.parseProfilePicture(html, "REVIEW"))
        assertNull(SocialProfilePictures.parseProfilePicture(html, "missing"))
        assertNull(SocialProfilePictures.parseProfilePicture(html.replace("s.cdninstagram.com", "evil.invalid"), "review"))
        assertNull(SocialProfilePictures.parseProfilePicture("<html>로그인 안내</html>", "review"))
    }

    /** 사진을 새로 받아도 DB 게시글 집계·수집 횟수는 변경하지 않습니다. */
    @Test fun refreshedPictureDoesNotIncreaseCollectedPosts() {
        val author = "review_pic"
        FrequentAccountStore.removeAccount(author)
        try {
            FrequentAccountStore.updateProfilePicture(author, "https://s.cdninstagram.com/fresh.jpg")
            val row = com.junkfood.seal.database.objects.DownloadedVideoInfo(1, "제목", author,
                "https://www.instagram.com/p/REVIEW_PIC/", "", "/a.png", "Instagram")
            val accounts = FrequentAccountStore.fromHistory(listOf(row, row.copy(id = 2, videoPath = "/b.png"))) { null }
            assertEquals(1, accounts.single().visitCount)
            assertEquals("https://s.cdninstagram.com/fresh.jpg", accounts.single().profilePicUrl)
            assertEquals(0, FrequentAccountStore.getAccounts().first { it.username == author }.visitCount)
        } finally { FrequentAccountStore.removeAccount(author) }
    }

    /** 자주 방문하는 계정의 등록, 방문 횟수 증가 및 정렬을 검증합니다. */
    @Test
    fun frequentAccountVisitAndSorting() {
        FrequentAccountStore.removeAccount("test_user_a")
        FrequentAccountStore.removeAccount("test_user_b")

        FrequentAccountStore.recordVisit("test_user_a", "https://example.com/pic_a.jpg")
        FrequentAccountStore.recordVisit("test_user_b", "https://example.com/pic_b.jpg")
        FrequentAccountStore.recordVisit("test_user_b") // b 방문 2회

        val accounts = FrequentAccountStore.getAccounts()
        val accountB = accounts.firstOrNull { it.username == "test_user_b" }
        val accountA = accounts.firstOrNull { it.username == "test_user_a" }

        assertNotNull(accountB)
        assertNotNull(accountA)
        assertEquals(2, accountB!!.visitCount)
        assertEquals(1, accountA!!.visitCount)
        assertEquals("https://example.com/pic_b.jpg", accountB.profilePicUrl)

        // 내림차순 정렬 검증: b가 a보다 앞에 위치해야 함
        val indexB = accounts.indexOf(accountB)
        val indexA = accounts.indexOf(accountA)
        assertTrue(indexB < indexA)

        // 삭제 검증
        FrequentAccountStore.removeAccount("test_user_a")
        assertNull(FrequentAccountStore.getAccounts().firstOrNull { it.username == "test_user_a" })
        FrequentAccountStore.removeAccount("test_user_b")
    }

    /** 게시글 메타데이터(프로필, 계정명, 본문, 해시태그) 저장 및 조회를 검증합니다. */
    @Test
    fun socialPostMetadataStorageAndRetrieval() {
        val testUrl = "https://www.instagram.com/p/TEST_CODE_123/?utm_source=ig_web"
        val metadata = SocialPostMetadata(
            postUrl = testUrl,
            author = "creator_test",
            authorProfilePic = "https://example.com/profile.jpg",
            text = "테스트 게시글 내용입니다! #개발 #테스트",
            hashtags = listOf("#개발", "#테스트")
        )

        SocialPostMetadataStore.save(metadata)
        val retrieved = SocialPostMetadataStore.get(testUrl)

        assertNotNull(retrieved)
        assertEquals("creator_test", retrieved!!.author)
        assertEquals("https://example.com/profile.jpg", retrieved.authorProfilePic)
        assertEquals(listOf("#개발", "#테스트"), retrieved.hashtags)

        // 쿼리 파라미터가 다른 동일 URL로도 조회가 가능해야 함 (정규화 검증)
        val cleanUrl = "https://www.instagram.com/p/TEST_CODE_123/"
        val retrievedClean = SocialPostMetadataStore.get(cleanUrl)
        assertNotNull(retrievedClean)
        assertEquals("creator_test", retrievedClean!!.author)
    }

    /** 플러그인 ID 열거형 및 기본 설정 검증 */
    @Test
    fun pluginIdsAndPreferences() {
        assertTrue(PluginId.entries.contains(PluginId.ACCOUNTS))
        assertTrue(PluginId.entries.contains(PluginId.POST_INFO))

        assertEquals("plugin_enabled_accounts", PluginId.ACCOUNTS.preferenceKey)
        assertEquals("plugin_enabled_post_info", PluginId.POST_INFO.preferenceKey)

        // 기본 설정 기본값 검증
        assertTrue(SocialPostMetadataPreferences.showProfile)
        assertTrue(SocialPostMetadataPreferences.showAuthor)
        assertTrue(SocialPostMetadataPreferences.showText)
        assertTrue(SocialPostMetadataPreferences.showHashtags)
    }

    /** 배경화면 설정 대상 상수 검증 */
    @Test
    fun wallpaperHelperConstants() {
        assertEquals(0, WallpaperHelper.TARGET_HOME)
        assertEquals(1, WallpaperHelper.TARGET_LOCK)
        assertEquals(2, WallpaperHelper.TARGET_BOTH)
    }
    /** 입력·링크 검증과 동시 방문 누락 및 서로 다른 게시글 키 충돌을 검사합니다. */
    @Test fun validationConcurrencyAndPostIdentity() {
        val username = "review_account_test"
        FrequentAccountStore.removeAccount(username)
        try {
            assertFalse(FrequentAccountStore.addAccount("../bad/path"))
            assertTrue(FrequentAccountStore.addAccount(" @$username "))
            val workers = List(2) { Thread { repeat(10) { FrequentAccountStore.recordVisit(username) } } }
            workers.forEach { it.start() }; workers.forEach { it.join() }
            assertEquals(21, FrequentAccountStore.getAccounts().single { it.username == username }.visitCount)
            assertTrue(WallpaperHelper.isInstagramUrl("https://www.instagram.com/p/ABC/"))
            assertFalse(WallpaperHelper.isInstagramUrl("intent://instagram.com/p/ABC"))
            assertFalse(WallpaperHelper.isInstagramUrl("https://instagram.com.evil.invalid/"))
            SocialPostMetadataStore.save(SocialPostMetadata("https://www.instagram.com/p/REVIEW_KEY/", author = "검사"))
            assertEquals("검사", SocialPostMetadataStore.get("https://instagram.com/reel/REVIEW_KEY/?utm_source=x#seal-media=1")?.author)
            SocialPostMetadataStore.save(SocialPostMetadata("https://youtube.com/watch?v=review_a", author = "A"))
            SocialPostMetadataStore.save(SocialPostMetadata("https://youtube.com/watch?v=review_b", author = "B"))
            assertEquals("A", SocialPostMetadataStore.get("https://youtube.com/watch?v=review_a")?.author)
        } finally { FrequentAccountStore.removeAccount(username) }
    }

}

