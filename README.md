<div>
    <div>
        <img src="https://img.shields.io/maven-central/v/io.github.remmerw/asen" alt="Kotlin Maven Version" />
        <img src="https://img.shields.io/badge/Platform-Android-brightgreen.svg?logo=android" alt="Badge Android" />
        <img src="https://img.shields.io/badge/Platform-iOS%20%2F%20macOS-lightgrey.svg?logo=apple" alt="Badge iOS" />
        <img src="https://img.shields.io/badge/Platform-JVM-8A2BE2.svg?logo=openjdk" alt="Badge JVM" />
    </div>
</div>


## Idun

A client-server implementation which supports pns URIs.
The connection is done via a TCP connection (but with **no** TLS encryption) between client and
server.

Finding peers via its pns-URI (which is basically just a peer ID) are described in detail in
the [Asen](https://github.com/remmerw/asen/) library.



## Integration

```
    
kotlin {
    sourceSets {
        commonMain.dependencies {
            ...
            implementation("io.github.remmerw:idun:0.2.7")
        }
        ...
    }
}
    
```

### Examples

```
    @Test
    fun simpleRequestResponse(): Unit = runBlocking {
        // create local server and client instance
        val port = TestEnv.randomPort()
        val storage = newStorage()
        val raw = storage.storeText("Moin") // store some text

        val server = newIdun()
        // run the service with the given port and with the data stored in storage
        server.runService(storage, port)

        val client = newIdun()
        
        // >>> make a direct connection possible (otherwise the asen functionality has to be used)
        assertTrue(
            client.reachable(
                TestEnv.loopbackPeeraddr(server.peerId(), port)
            )
        )
        // <<< end
        

        val data = client.fetchData(server.peerId(), raw.cid()) // fetch request
        assertEquals(data.decodeToString(), "Moin")

        client.shutdown()
        server.shutdown()
        storage.delete() // Note: this one deletes all content in the storage
    }
```
