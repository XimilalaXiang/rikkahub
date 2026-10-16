package me.rerere.rikkahub.sync

import org.koin.dsl.module

/** Koin 注册(接入点 A1)。 */
val syncModule = module {
    single { SyncRuntime(get(), get(), get(), get(), get(), get()) }
}
