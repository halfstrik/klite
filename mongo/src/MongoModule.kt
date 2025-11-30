package klite.mongo

import klite.*
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients

open class MongoModule(val mongoClient: MongoClient = MongoClients.create(Config["MONGO_URL"])): Extension {
  override fun install(server: Server) = server.run {
    registry.register<MongoClient>(mongoClient)
//    errors.on(AlreadyExistsException::class, Conflict)
    onStop { (mongoClient as? AutoCloseable)?.close() }
  }
}
