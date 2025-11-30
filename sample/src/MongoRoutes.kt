import klite.annotations.GET
import klite.annotations.Path
import poles.PollRepository

@Path("/mongo")
class MongoRoutes(private val pollRepository: PollRepository) {
  @GET("/all") fun polls() = pollRepository.all()
}
