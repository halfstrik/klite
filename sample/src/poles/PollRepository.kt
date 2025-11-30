package poles

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import org.bson.Document

class PollRepository(mongoClient: MongoClient) {
  private val database = mongoClient.getDatabase("mydatabase")
  private val collection: MongoCollection<Document> = database.getCollection("mycollection")

  fun all(): List<Document> =
    collection.find().toList()
}
