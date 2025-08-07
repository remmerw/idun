<div>
    <div>
        <img src="https://img.shields.io/maven-central/v/io.github.remmerw/idun" alt="Kotlin Maven Version" />
        <img src="https://img.shields.io/badge/Platform-Android-brightgreen.svg?logo=android" alt="Badge Android" />
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
            implementation("io.github.remmerw:idun:0.5.5")
        }
        ...
    }
}
    
```

### Examples

```
    @Test
    fun simpleRequestResponse(): Unit = runBlocking(Dispatchers.IO) {
       
        val storage = newStorage()
        val raw = storage.storeText("Moin") // store some text

        val server = newIdun()
        server.startup(storage = storage)


        val addresses = server.observedAddresses()
        checkNotNull(addresses)
        println("Publish addresses ${addresses.size}")


        // publish your addresses
        server.publishAddresses(addresses, 25, 60)


        println("Num reservations " + server.numReservations())
        assertTrue(server.numReservations() > 0)


        val client = newIdun()

        val data = client.fetchRaw(server.peerId(), raw.cid())
        assertEquals(data.decodeToString(), "Moin")

        client.shutdown()
        server.shutdown()
        storage.delete() // Note: this one deletes all content in the storage
    }
    
```
