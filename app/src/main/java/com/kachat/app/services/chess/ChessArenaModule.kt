package com.kachat.app.services.chess

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The chess services' view of the Public Chats service ([ChessArenaChannel]), bound here so the
 *  app's own modules need no chess entry. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ChessArenaModule {
    @Binds
    abstract fun bindChessArenaChannel(impl: PublicChatsChessArena): ChessArenaChannel
}
