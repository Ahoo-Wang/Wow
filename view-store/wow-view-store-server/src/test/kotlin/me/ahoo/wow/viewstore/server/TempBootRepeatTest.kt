package me.ahoo.wow.viewstore.server

import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource

// TEMP diag (DO NOT MERGE): the boot test, each in a fresh context.
@TestPropertySource(properties = ["diag.boot=1"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat01Test : ViewStoreServerBootTest()

@TestPropertySource(properties = ["diag.boot=2"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat02Test : ViewStoreServerBootTest()

@TestPropertySource(properties = ["diag.boot=3"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat03Test : ViewStoreServerBootTest()

@TestPropertySource(properties = ["diag.boot=4"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat04Test : ViewStoreServerBootTest()

@TestPropertySource(properties = ["diag.boot=5"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat05Test : ViewStoreServerBootTest()

@TestPropertySource(properties = ["diag.boot=6"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat06Test : ViewStoreServerBootTest()

@TestPropertySource(properties = ["diag.boot=7"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat07Test : ViewStoreServerBootTest()

@TestPropertySource(properties = ["diag.boot=8"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat08Test : ViewStoreServerBootTest()

@TestPropertySource(properties = ["diag.boot=9"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat09Test : ViewStoreServerBootTest()

@TestPropertySource(properties = ["diag.boot=10"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat10Test : ViewStoreServerBootTest()

@TestPropertySource(properties = ["diag.boot=11"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat11Test : ViewStoreServerBootTest()

@TestPropertySource(properties = ["diag.boot=12"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat12Test : ViewStoreServerBootTest()

@TestPropertySource(properties = ["diag.boot=13"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat13Test : ViewStoreServerBootTest()

@TestPropertySource(properties = ["diag.boot=14"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat14Test : ViewStoreServerBootTest()

@TestPropertySource(properties = ["diag.boot=15"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat15Test : ViewStoreServerBootTest()

@TestPropertySource(properties = ["diag.boot=16"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat16Test : ViewStoreServerBootTest()

@TestPropertySource(properties = ["diag.boot=17"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat17Test : ViewStoreServerBootTest()

@TestPropertySource(properties = ["diag.boot=18"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat18Test : ViewStoreServerBootTest()

@TestPropertySource(properties = ["diag.boot=19"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat19Test : ViewStoreServerBootTest()

@TestPropertySource(properties = ["diag.boot=20"])
@DirtiesContext
@Import(ViewStoreServerBootTest.NoSharedBoards::class)
class TempBootRepeat20Test : ViewStoreServerBootTest()
