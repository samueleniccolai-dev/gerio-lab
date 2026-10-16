package it.gerio.platform.acceptance;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import io.restassured.response.Response;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * Giro 016 (docs/lab/giri/016-piano.md): the terms of sale of a shop, as a page and as a PDF, and
 * the PDF attached to the confirmation email. PDFBox and the mock mailbox are reached by name, so
 * that the tests compile before the dependency exists: a missing class or method is an assertion
 * failure, not an error.
 */
final class Giro016Terms {

  static final String VERSION = "2026-10-12";
  static final String PAGE = "/condizioni-di-vendita";
  static final String PDF = "/condizioni-di-vendita.pdf";
  static final String FILE_NAME = "condizioni-di-vendita-" + VERSION + ".pdf";

  /** The five section titles of the plan, in order. */
  static final List<String> TITLES =
      List.of(
          "Venditore",
          "Prezzi e pagamento",
          "Consegna e ritiro",
          "Diritto di recesso",
          "Garanzia legale e reclami");

  static final String VERSION_LINE = "Versione " + VERSION;

  /** A sentence of the withdrawal section with an accented letter (WinAnsi in the PDF). */
  static final String ACCENTED = "Il diritto di recesso è escluso";

  static final String UNKNOWN_HOST = "sconosciuto.localhost";

  private static final String MAILBOX = "io.quarkus.mailer.MockMailbox";

  /** An attachment of a sent mail: name, content type and bytes. */
  record Attached(String name, String contentType, byte[] data) {}

  private Giro016Terms() {}

  // --- HTTP -----------------------------------------------------------------------------------

  static Response get(String host, String path) {
    return given().redirects().follow(false).header("Host", host).when().get(path);
  }

  /** The PDF of the shop's terms, checked for status, type and magic number. */
  static byte[] pdf(String host) {
    Response response = get(host, PDF);
    assertEquals(200, response.statusCode(), "GET " + PDF + " on " + host);
    String type = response.getContentType();
    assertTrue(
        type != null && type.startsWith("application/pdf"),
        "Content-Type on " + host + ": " + type);
    byte[] body = response.asByteArray();
    assertPdf(body, "GET " + PDF + " on " + host);
    return body;
  }

  static void assertPdf(byte[] bytes, String what) {
    assertNotNull(bytes, what + ": no bytes");
    String head = new String(bytes, 0, Math.min(5, bytes.length), StandardCharsets.ISO_8859_1);
    assertEquals("%PDF-", head, what + ": not a PDF");
  }

  // --- PDF text -------------------------------------------------------------------------------

  /** The text of the PDF, read with PDFBox 3; white space runs (NBSP too) become one space. */
  static String text(byte[] pdf) {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    Class<?> loaderType;
    Class<?> documentType;
    Class<?> stripperType;
    try {
      loaderType = Class.forName("org.apache.pdfbox.Loader", true, loader);
      documentType = Class.forName("org.apache.pdfbox.pdmodel.PDDocument", true, loader);
      stripperType = Class.forName("org.apache.pdfbox.text.PDFTextStripper", true, loader);
    } catch (ClassNotFoundException e) {
      fail("PDFBox 3 is not on the classpath (" + e.getMessage() + ")");
      return null;
    }
    try {
      Object document = loaderType.getMethod("loadPDF", byte[].class).invoke(null, (Object) pdf);
      try (Closeable closeable = (Closeable) document) {
        Object stripper = stripperType.getConstructor().newInstance();
        String text =
            (String) stripperType.getMethod("getText", documentType).invoke(stripper, document);
        return text.replaceAll("[\\s\\u00a0]+", " ");
      }
    } catch (InvocationTargetException e) {
      throw new AssertionError("PDFBox could not read the PDF: " + e.getCause(), e.getCause());
    } catch (ReflectiveOperationException | IOException e) {
      throw new AssertionError("PDFBox could not read the PDF: " + e, e);
    }
  }

  // --- mailbox --------------------------------------------------------------------------------

  /** The attachments of each mail sent to the address, one list per mail, in sending order. */
  static List<List<Attached>> attachmentsOfMailsTo(String address) {
    Object mailbox = bean(MAILBOX);
    List<List<Attached>> all = new ArrayList<>();
    for (Object mail :
        (List<?>) call(mailbox, "getMailsSentTo", new Class<?>[] {String.class}, address)) {
      all.add(attachments(mail));
    }
    return all;
  }

  /** The attachments of the mail sent to the address whose subject contains the given text. */
  static List<Attached> attachmentsOfMailTo(String address, String subjectPart) {
    Object mailbox = bean(MAILBOX);
    for (Object mail :
        (List<?>) call(mailbox, "getMailsSentTo", new Class<?>[] {String.class}, address)) {
      String subject = (String) call(mail, "getSubject", new Class<?>[0]);
      if (subject != null && subject.contains(subjectPart)) {
        return attachments(mail);
      }
    }
    throw new AssertionError(
        "no mail to " + address + " with «" + subjectPart + "» in the subject");
  }

  private static List<Attached> attachments(Object mail) {
    List<Attached> found = new ArrayList<>();
    Object list = call(mail, "getAttachments", new Class<?>[0]);
    if (list == null) {
      return found;
    }
    for (Object attachment : (List<?>) list) {
      found.add(
          new Attached(
              (String) call(attachment, "getName", new Class<?>[0]),
              (String) call(attachment, "getContentType", new Class<?>[0]),
              bytes(attachment)));
    }
    return found;
  }

  private static byte[] bytes(Object attachment) {
    Object file = call(attachment, "getFile", new Class<?>[0]);
    if (file instanceof File f) {
      try {
        return Files.readAllBytes(f.toPath());
      } catch (IOException e) {
        throw new AssertionError("cannot read the attached file " + f, e);
      }
    }
    Object data = call(attachment, "getData", new Class<?>[0]);
    if (!(data instanceof Flow.Publisher<?> publisher)) {
      throw new AssertionError("attachment without data: " + data);
    }
    return collect(publisher);
  }

  private static byte[] collect(Flow.Publisher<?> publisher) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    CompletableFuture<byte[]> done = new CompletableFuture<>();
    publisher.subscribe(
        new Flow.Subscriber<Object>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(Object item) {
            out.write((Byte) item);
          }

          @Override
          public void onError(Throwable throwable) {
            done.completeExceptionally(throwable);
          }

          @Override
          public void onComplete() {
            done.complete(out.toByteArray());
          }
        });
    try {
      return done.get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new AssertionError("cannot read the attachment's data: " + e, e);
    }
  }

  // --- reflection -----------------------------------------------------------------------------

  private static Object bean(String className) {
    Class<?> type;
    try {
      type = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
    } catch (ClassNotFoundException e) {
      fail("class " + className + " is not on the classpath");
      return null;
    }
    InstanceHandle<?> handle = Arc.container().instance(type);
    if (!handle.isAvailable()) {
      fail("no CDI bean of type " + className);
    }
    return handle.get();
  }

  private static Object call(Object target, String name, Class<?>[] types, Object... args) {
    try {
      Method method = target.getClass().getMethod(name, types);
      method.setAccessible(true);
      return method.invoke(target, args);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError("cannot call " + name + " on " + target.getClass(), e);
    } catch (InvocationTargetException e) {
      throw new AssertionError(name + " threw " + e.getCause(), e.getCause());
    }
  }
}
