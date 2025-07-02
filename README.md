<div>
    <div>
        <img src="https://img.shields.io/maven-central/v/io.github.remmerw/idun" alt="Kotlin Maven Version" />
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
            implementation("io.github.remmerw:idun:0.3.2")
        }
        ...
    }
}
    
```

### Examples

```

    @Test
    fun simpleRequestResponse(): Unit = runBlocking(Dispatchers.IO) {

        val port = TestEnv.randomPort()
        val storage = newStorage()
        val raw = storage.storeText("Moin") // store some text

        val server = newIdun()


        val address = server.observedAddress()
        checkNotNull(address)
        println("public address ${address.size}")

        val peeraddr = if (address.size == 16) { // ipv6 might be ok to dial
            createPeeraddr(
                server.peerId(),
                address, port.toUShort()
            )
        } else { // ip4 is probably not a dialable IP (so for testing just 127.0.0.1)
            createPeeraddr(
                server.peerId(),
                byteArrayOf(127, 0, 0, 1), port.toUShort()
            )
        }

        // startup the service
        server.startup(storage, listOf(peeraddr), port, 25, 60)

        delay(60000) // wait for 60 sec for server to settle

        println("Num reservations " + server.numReservations())
        assertTrue(server.hasReservations())

        val client = newIdun()

        val data = client.fetchData(server.peerId(), raw.cid())
        assertEquals(data.decodeToString(), "Moin")

        client.shutdown()
        server.shutdown()
        storage.delete() // Note: this one deletes all content in the storage
    }
    
```
