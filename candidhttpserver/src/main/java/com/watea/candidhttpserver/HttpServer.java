/*
 * Copyright (c) 2024. Stephane Treuchot
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to
 * do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package com.watea.candidhttpserver;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

// Minimalist HTTP/1.1 server, single-use (cannot be restarted after stop()).
// Request body is not available to handlers, one request per connection.
@SuppressWarnings("unused")
public class HttpServer implements Closeable {
  private static final String LOG_TAG = HttpServer.class.getSimpleName();
  private static final String HTTP_VERSION = "HTTP/1.1";
  private static final String END = "\r\n";
  private static final String SEPARATOR = ": ";
  // Timeout applied to each accepted client socket to prevent slow/stalled
  // connections from holding a thread indefinitely
  private static final int CLIENT_READ_TIMEOUT_MS = 5000;
  // Protection against oversized requests
  private static final int MAX_LINE_LENGTH = 8192;
  private static final int MAX_HEADERS = 100;
  @NonNull
  private final ServerSocket serverSocket;
  // Thread-safe as handlers may be added while serving; tried in registration order
  private final List<Handler> handlers = new CopyOnWriteArrayList<>();
  // Unbounded: each connection, streaming included, holds one thread
  private final ExecutorService executor = Executors.newCachedThreadPool(new ThreadFactory());
  private final AtomicBoolean isStarted = new AtomicBoolean(false);
  // volatile: written by stop() (caller thread) and read by the accept-loop thread
  private volatile boolean isStopped = false;

  public HttpServer() throws IOException {
    this(0);
  }

  // 0 for an ephemeral port
  public HttpServer(int port) throws IOException {
    serverSocket = new ServerSocket(port);
  }

  @NonNull
  private static byte[] getBytes(@NonNull String string) {
    return string.getBytes(StandardCharsets.UTF_8);
  }

  private static void closeQuietly(@NonNull Socket socket) {
    try {
      socket.close();
    } catch (IOException iOException) {
      Log.e(LOG_TAG, "closeQuietly: failed to close client socket", iOException);
    }
  }

  // Line terminated by LF, CR stripped, ISO-8859-1 (one byte = one char).
  // Returns null on end of stream or if line is too long.
  @Nullable
  private static String readLine(@NonNull InputStream inputStream) throws IOException {
    final StringBuilder line = new StringBuilder();
    int b;
    while ((b = inputStream.read()) != -1) {
      if (b == '\n') {
        final int length = line.length();
        if ((length > 0) && (line.charAt(length - 1) == '\r')) {
          line.setLength(length - 1);
        }
        return line.toString();
      }
      if (line.length() >= MAX_LINE_LENGTH) {
        Log.w(LOG_TAG, "readLine: line too long");
        return null;
      }
      line.append((char) b);
    }
    // End of stream: return partial line if any
    return (line.length() == 0) ? null : line.toString();
  }

  @Nullable
  private static Request parseRequest(@NonNull InputStream inputStream) throws IOException {
    // Parse the request line
    final String requestLine = readLine(inputStream);
    if ((requestLine == null) || requestLine.isEmpty()) {
      return null;
    }
    final String[] requestParts = requestLine.split(" ");
    if (requestParts.length != 3) {
      return null;
    }
    for (final String part : requestParts) {
      if (part.isEmpty()) {
        return null;
      }
    }
    final Request request = new Request(requestParts[0], requestParts[1], requestParts[2]);
    // Parse headers until the blank line that separates headers from body
    int headerCount = 0;
    while (true) {
      final String line = readLine(inputStream);
      // Connection closed or line too long
      if (line == null) {
        return null;
      }
      if (line.isEmpty()) {
        return request;
      }
      if (++headerCount > MAX_HEADERS) {
        Log.w(LOG_TAG, "parseRequest: too many headers");
        return null;
      }
      final int colon = line.indexOf(':');
      if (colon > 0) {
        request.addHeader(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
      }
    }
  }

  private static void sendError(
    @NonNull Response response,
    int code,
    @NonNull String message) throws IOException {
    response.setStatus(code, message);
    response.addHeader(Response.CONTENT_LENGTH, "0");
    response.send();
  }

  public void addHandler(@NonNull Handler handler) {
    handlers.add(handler);
  }

  // No effect if already started, IllegalStateException if stopped
  public void start() {
    Log.d(LOG_TAG, "start");
    if (isStopped) {
      throw new IllegalStateException("HttpServer has been stopped; create a new instance");
    }
    if (!isStarted.compareAndSet(false, true)) {
      Log.w(LOG_TAG, "start: already started");
      return;
    }
    final Thread acceptThread = new Thread(this::acceptLoop, "CandidHttpServer-accept");
    acceptThread.setDaemon(true);
    acceptThread.start();
  }

  // Idempotent
  public void stop() throws IOException {
    Log.d(LOG_TAG, "stop");
    isStopped = true;
    // Closing the server socket unblocks accept()
    try {
      serverSocket.close();
    } finally {
      executor.shutdownNow();
    }
  }

  @Override
  public void close() throws IOException {
    stop();
  }

  public int getListeningPort() {
    return serverSocket.getLocalPort();
  }

  private void acceptLoop() {
    while (!isStopped && !serverSocket.isClosed()) {
      final Socket socket;
      try {
        socket = serverSocket.accept();
      } catch (IOException iOException) {
        if (!isStopped && !serverSocket.isClosed()) {
          Log.e(LOG_TAG, "acceptLoop: failed to accept connection", iOException);
        }
        continue;
      }
      try {
        executor.execute(() -> {
          try {
            handleClient(socket);
          } finally {
            // Always close the socket, even if handleClient throws
            closeQuietly(socket);
          }
        });
      } catch (RejectedExecutionException rejectedExecutionException) {
        // Server stopped between accept() and execute()
        closeQuietly(socket);
      }
    }
    Log.d(LOG_TAG, "acceptLoop: server stopped");
  }

  private void handleClient(@NonNull Socket socket) {
    try {
      // Guard against slow or stalled clients blocking a thread forever
      socket.setSoTimeout(CLIENT_READ_TIMEOUT_MS);
      final InputStream inputStream = new BufferedInputStream(socket.getInputStream());
      final OutputStream outputStream = socket.getOutputStream();
      final Response response = new Response(outputStream);
      final Request request = parseRequest(inputStream);
      if (request == null) {
        sendError(response, 400, "Bad Request");
      } else {
        try {
          for (final Handler handler : handlers) {
            handler.handle(request, response);
            // Stop at first handler that sent a response
            if (response.isClientHandled()) {
              break;
            }
          }
          if (!response.isClientHandled()) {
            sendError(response, 404, "Not Found");
          }
        } catch (RuntimeException runtimeException) {
          Log.e(LOG_TAG, "handleClient: handler failed", runtimeException);
          if (!response.isClientHandled()) {
            sendError(response, 500, "Internal Server Error");
          }
        }
      }
      outputStream.flush();
    } catch (IOException iOException) {
      Log.e(LOG_TAG, "handleClient: failed", iOException);
    }
  }

  // Handlers are tried in registration order until one calls Response.send(), else 404
  public interface Handler {
    void handle(@NonNull Request request, @NonNull Response response) throws IOException;
  }

  public static class Request {
    @NonNull
    private final String method;
    @NonNull
    private final String rawPath;
    @NonNull
    private final Uri uri;
    @NonNull
    private final String protocol;
    // HTTP header names are case-insensitive
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    private Request(@NonNull String method, @NonNull String rawPath, @NonNull String protocol) {
      this.method = method;
      this.rawPath = rawPath;
      uri = Uri.parse(this.rawPath);
      this.protocol = protocol;
    }

    @NonNull
    public String getMethod() {
      return method;
    }

    @NonNull
    public String getPath() {
      final String path = uri.getPath();
      return (path == null) ? "" : path;
    }

    @NonNull
    public String getProtocol() {
      return protocol;
    }

    // Read-only, case-insensitive keys
    @NonNull
    public Map<String, String> getHeaders() {
      return Collections.unmodifiableMap(headers);
    }

    // Case-insensitive
    @Nullable
    public String getHeader(@NonNull String name) {
      return headers.get(name);
    }

    @Nullable
    public String getParam(@NonNull String key) {
      return uri.getQueryParameter(key);
    }

    @NonNull
    public String getRawPath() {
      return rawPath;
    }

    // Repeated headers are combined with ", "
    private void addHeader(@NonNull String name, @NonNull String value) {
      headers.merge(name, value, (previous, added) -> previous + ", " + added);
    }
  }

  public static class Response {
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String CONTENT_LENGTH = "Content-Length";
    private static final String CONNECTION = "Connection";
    // Insertion order is preserved when sending
    private final Map<String, String> headers = new LinkedHashMap<>();
    @NonNull
    private final OutputStream outputStream;
    private boolean isClientHandled = false;
    // 200 OK by default
    private int statusCode = 200;
    @NonNull
    private String statusMessage = "OK";

    private Response(@NonNull OutputStream outputStream) {
      this.outputStream = outputStream;
    }

    public void addHeader(@NonNull String key, @NonNull String value) {
      headers.put(key, value);
    }

    // Must be called before send
    public void setStatus(int code, @NonNull String message) {
      checkNotSent();
      this.statusCode = code;
      this.statusMessage = message;
    }

    // Must be called once, before writing the body
    public void send() throws IOException {
      checkNotSent();
      isClientHandled = true;
      if (!hasConnectionHeader()) {
        headers.put(CONNECTION, "close");
      }
      // Single write to avoid many small TCP packets
      final StringBuilder head = new StringBuilder()
        .append(HTTP_VERSION).append(' ').append(statusCode).append(' ').append(statusMessage)
        .append(END);
      for (final Map.Entry<String, String> entry : headers.entrySet()) {
        head.append(entry.getKey()).append(SEPARATOR).append(entry.getValue()).append(END);
      }
      head.append(END);
      outputStream.write(getBytes(head.toString()));
    }

    // Must be called after send
    @NonNull
    public OutputStream getOutputStream() {
      if (!isClientHandled) {
        throw new IllegalStateException("send() must be called before writing the body");
      }
      return outputStream;
    }

    private boolean isClientHandled() {
      return isClientHandled;
    }

    private boolean hasConnectionHeader() {
      for (final String key : headers.keySet()) {
        if (key.equalsIgnoreCase(CONNECTION)) {
          return true;
        }
      }
      return false;
    }

    private void checkNotSent() {
      if (isClientHandled) {
        throw new IllegalStateException("Response already sent");
      }
    }
  }

  private static class ThreadFactory implements java.util.concurrent.ThreadFactory {
    private final AtomicInteger count = new AtomicInteger();

    @Override
    public Thread newThread(@NonNull Runnable runnable) {
      final Thread thread = new Thread(runnable, "CandidHttpServer-worker-" + count.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  }
}