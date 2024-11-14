# CandidHttpServer

**CandidHttpServer** is a lightweight and easy-to-use library for setting up a minimalist HTTP server on Android. It’s ideal for applications that require a local HTTP server, such as sharing media or data over a local network.

## Installation

### Using GitHub Packages

To use this library in your project from GitHub Packages, add the following to your `build.gradle.kts` file. Replace `TAG` with the version tag of the release you want to use.

1. Add the GitHub Packages repository to your project’s repositories, referencing your GitHub token from an environment variable (e.g., `GITHUB_TOKEN`).

```kotlin
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

```kotlin
dependencies {
    implementation("com.watea.CandidHttpServer:CandidHttpServer:TAG")
}
```

> **Note:** Replace `YOUR_USERNAME` and `YOUR_REPOSITORY` with your GitHub username and repository name. Ensure that the `GITHUB_TOKEN` environment variable is set on your system with `read:packages` permissions.

### Using JitPack

You can also include this library using [JitPack](https://jitpack.io/).

1. Add the JitPack repository to your project’s repositories:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}
```

2. Add the dependency using the JitPack format:

```kotlin
dependencies {
    implementation("com.github.watea:CandidHttpServer:latest.release")
}
```

## Usage

### Setting Up `HttpServer`

The library provides an `HttpServer` class that can be instantiated and started on a local port to listen for incoming connections.

### Example: Server Setup

```java
import com.watea.CandidHttpServer.HttpServer;
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

### Creating a `Handler`

Create a custom `Handler` by implementing the `HttpServer.Handler` interface. Below is an example of an image resource handler, `ResourceHandler`:

```java
import com.watea.CandidHttpServer.HttpServer;
import android.graphics.Bitmap;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class ResourceHandler implements HttpServer.Handler {
    private Bitmap bitmap;
    private String uri;

    public String createLogoFile(Bitmap image, int id) {
        bitmap = Bitmap.createScaledBitmap(image, 300, 300, true);
        return uri = "logo" + id + ".jpg";
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

A more complete example of using **CandidHttpServer** in a real application can be found in the **[RadioUpnp project](https://github.com/watea/RadioUpnp)**. This project demonstrates how to integrate and extend the library in a full Android application.

## Discussion

**CandidHttpServer** is designed for simplicity and minimal footprint, making it suitable for basic use cases and small-scale applications on Android. However, it may not be ideal for more complex use cases, such as handling extensive concurrent connections or advanced HTTP features (e.g., HTTPS, complex request routing, or advanced error handling).

For more advanced needs, you may consider **NanoHTTPD** (https://github.com/NanoHttpd/nanohttpd), a robust, lightweight HTTP server library for Java. **NanoHTTPD** provides a richer feature set, including support for file uploads, custom MIME types, and request parsing, making it more suitable for applications with more comprehensive requirements.

## License

MIT