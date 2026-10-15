package it.gerio.platform.acceptance;

import static org.junit.jupiter.api.Assertions.fail;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Giro 015 (docs/lab/giri/015-piano.md): the outbox worker and the mock mailbox of {@code
 * quarkus-mailer}, reached by name so that the tests compile before both exist. A missing class,
 * bean or method is an assertion failure, not an error.
 */
final class Giro015Mail {

  static final String WORKER = "it.gerio.platform.notifications.OutboxWorker";
  static final String MAILBOX = "io.quarkus.mailer.MockMailbox";

  /** What the tests read of a sent {@code io.quarkus.mailer.Mail}. */
  record Sent(
      String from, List<String> to, String replyTo, String subject, String html, String text) {

    /** HTML as text (tags out, common entities decoded) followed by the text part. */
    String bodies() {
      return Giro015Mail.text(html) + "\n" + (text == null ? "" : text);
    }
  }

  private Giro015Mail() {}

  // --- worker ---------------------------------------------------------------------------------

  /** One pass of the outbox worker, as the timer would run it. */
  static void drainOnce() {
    Object worker = bean(WORKER);
    Method drain;
    try {
      drain = worker.getClass().getMethod("drainOnce");
    } catch (NoSuchMethodException e) {
      fail(WORKER + " has no public drainOnce()");
      return;
    }
    try {
      drain.invoke(worker);
    } catch (InvocationTargetException e) {
      throw new AssertionError("drainOnce() threw " + e.getCause(), e.getCause());
    } catch (IllegalAccessException e) {
      throw new AssertionError("drainOnce() is not accessible", e);
    }
  }

  // --- mailbox --------------------------------------------------------------------------------

  static void clear() {
    call(bean(MAILBOX), "clear", new Class<?>[0]);
  }

  static int total() {
    return (Integer) call(bean(MAILBOX), "getTotalMessagesSent", new Class<?>[0]);
  }

  static List<Sent> sentTo(String address) {
    Object mails = call(bean(MAILBOX), "getMailsSentTo", new Class<?>[] {String.class}, address);
    List<Sent> sent = new ArrayList<>();
    for (Object mail : (List<?>) mails) {
      sent.add(
          new Sent(
              (String) call(mail, "getFrom", new Class<?>[0]),
              strings(call(mail, "getTo", new Class<?>[0])),
              (String) call(mail, "getReplyTo", new Class<?>[0]),
              (String) call(mail, "getSubject", new Class<?>[0]),
              (String) call(mail, "getHtml", new Class<?>[0]),
              (String) call(mail, "getText", new Class<?>[0])));
    }
    return sent;
  }

  // --- text -----------------------------------------------------------------------------------

  private static final Pattern TAG = Pattern.compile("<[^>]*>");

  /** HTML as text: tags become spaces, common entities decoded, white space runs (not NBSP) one. */
  static String text(String html) {
    if (html == null) {
      return "";
    }
    String text = TAG.matcher(html).replaceAll(" ");
    text =
        text.replace("&nbsp;", " ")
            .replace("&#160;", " ")
            .replace("&euro;", "€")
            .replace("&#8364;", "€")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&");
    return text.replaceAll("[ \\t\\r\\n]+", " ");
  }

  /** A link to the status page, {@code https://<host>/ordine/<token>}: groups host and token. */
  static final Pattern STATUS_LINK =
      Pattern.compile("https://([a-z0-9.-]+)/ordine/([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");

  /** All status links of the text (host + "/" + token). */
  static List<String> statusLinks(String text) {
    List<String> links = new ArrayList<>();
    Matcher m = STATUS_LINK.matcher(text);
    while (m.find()) {
      links.add(m.group(1) + "/" + m.group(2));
    }
    return links;
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

  private static List<String> strings(Object list) {
    List<String> out = new ArrayList<>();
    if (list != null) {
      for (Object o : (List<?>) list) {
        out.add(String.valueOf(o));
      }
    }
    return out;
  }
}
