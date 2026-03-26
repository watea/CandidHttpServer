
# CandidHttpServer

**CandidHttpServer** is a -one file- lightweight and easy-to-use library for setting up a minimalist HTTP server on Android. It is ideal for applications requiring a local HTTP server, such as media or data sharing over a local network.


## Installation

### Using GitHub Packages

To use this library in your project from GitHub Packages, add the following lines to your `build.gradle` file. Replace `TAG` with the version tag of the release you want to use.

1. Add the GitHub Packages repository to your project’s repositories by referencing your GitHub token from an environment variable (e.g., `GITHUB_TOKEN`).

```groovy
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/YOUR_USERNAME/YOUR_REPOSITORY")
        credentials {
            username = "YOUR_USERNAME"
            password = System.getenv("GITHUB_TOKEN") ?: ""
        }
    }
}
```

2. Add the dependency.

```groovy
dependencies {
    implementation 'com.watea.candidhttpserver:TAG'
}
```

> **Note:** Replace `YOUR_USERNAME` and `YOUR_REPOSITORY` with your GitHub username and the repository name. Ensure that the `GITHUB_TOKEN` environment variable is set on your system with `read:packages` permissions.

### Using JitPack

Alternatively, you can include this library using [JitPack](https://jitpack.io/).

1. Add the JitPack repository to your top-level `settings.gradle` file:

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

2. Add the dependency in your module’s `build.gradle` file:

```groovy
dependencies {
    implementation 'com.github.watea:candidhttpserver:TAG'
}
```

## Usage

### Configuring the `HttpServer`

The library provides an `HttpServer` class that can be instantiated and started on a local port to listen for incoming connections.

### Example: Setting up the Server

```java
import com.watea.candidhttpserver.HttpServer;
import java.io.IOException;

public class MyHttpServer {
    private HttpServer server;

    public void startServer() {
        try {
            server = new HttpServer();
            server.addHandler(new ResourceHandler());
            server.start();
            int port = server.getListeningPort();
            System.out.println("Server is listening on port: " + port);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stopServer() {
        if (server != null) {
            server.stop();
        }
    }
}
```

Note: you may also decide to set the driver artifacts in constructor, it is matter of design. 

### Creating a Custom `Handler`

Create a custom `Handler` by implementing the `HttpServer.Handler` interface. Here’s an example of a resource handler, `ResourceHandler`:

```java
import com.watea.candidhttpserver.HttpServer;
import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class ResourceHandler implements HttpServer.Handler {
    private Bitmap bitmap;
    private String uri;

    public String createLogoFile(Bitmap image, int id) {
        bitmap = Bitmap.createScaledBitmap(image, 300, 300, true);
        uri = "logo" + id + ".jpg";
        return uri;
    }

    @Override
    public void handle(HttpServer.Request request, HttpServer.Response response, OutputStream responseStream) throws IOException {
        if (bitmap == null || !request.getPath().endsWith(uri)) return;

        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream);
            byte[] imageData = byteArrayOutputStream.toByteArray();
            response.addHeader("Content-Type", "image/jpeg");
            response.addHeader("Content-Length", String.valueOf(imageData.length));
            response.send();
            responseStream.write(imageData);
        }
    }
}
```

### Starting the Server with `ResourceHandler`

1. Create an instance of `HttpServer`.
2. Add an instance of `ResourceHandler` as a `Handler`.
3. Start the server.

```java
MyHttpServer httpServer = new MyHttpServer();
httpServer.startServer();
```

### Complete Example

A more complete example of using **CandidHttpServer** in a real-world application can be found in the project **[RadioUpnp](https://github.com/watea/RadioUpnp)**. This project demonstrates how to integrate and extend the library.

## Discussion

The used primitives and .gradle integration have been foreseen to work seamlessly in Android environment. But you may adapt and use the library in any other projects.

**CandidHttpServer** is designed for simplicity and minimal footprint, making it suitable for basic use cases and small-scale applications on Android. However, it may not be ideal for more complex use cases, such as handling extensive concurrent connections or advanced HTTP features (e.g., HTTPS, complex request routing, or advanced error handling).

For more advanced needs, you may consider **NanoHTTPD** (https://github.com/NanoHttpd/nanohttpd), a robust, lightweight HTTP server library for Java. **NanoHTTPD** provides a richer feature set, including support for file uploads, custom MIME types, and request parsing, making it more suitable for applications with more comprehensive requirements.

## License

MIT