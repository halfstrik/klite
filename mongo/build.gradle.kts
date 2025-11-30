dependencies {
  api(project(":core"))
  compileOnly(project(":server"))
  compileOnly(libs.mongodb)
  testImplementation(project(":core"))
  testImplementation(project(":server"))
}
