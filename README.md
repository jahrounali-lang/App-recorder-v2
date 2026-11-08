# ScreenRecorder (flat layout)

All files are in the repo root. The GitHub Actions workflow rebuilds the
folder structure automatically before building.
Naming rule: "__" in a file name = folder separator.
Example: res__values__strings.xml -> app/src/main/res/values/strings.xml
Files starting with java__ or res__, and AndroidManifest.xml, go under app/src/main/.
app__build.gradle.kts -> app/build.gradle.kts.
