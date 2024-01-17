# klite-oauth

Experimental helpers to implement OAuth

You need to provide implementations of [OAuthUser and OAuthUserRepository](src/OAuthUser.kt).

```kotlin
context("/oauth") {
  register<OAuthUserRepository>(UserRepository::class)
  register(OAuthClient(GOOGLE, httpClient))
  annotated<OAuthRoutes>()
}
```

Then navigate to `/oauth` or `/oauth?redirect=/return/path` to start authentication.