# Ricerca giro 016 — PDF delle condizioni

## Casi reali e documentazione
- Maven Central: ultima versione di `io.github.openhtmltopdf:openhtmltopdf-pdfbox` = **1.1.97** (metadati aggiornati 09/10/2026); precedenti 1.1.93–1.1.96.
- Licenza dal POM 1.1.97: **LGPL 2.1 o successiva**. Il modulo PDF/A è GPL, non su Maven Central, non serve.
- Dipendenze dal POM: `pdfbox` 3.0.7, `xmpbox` 3.0.7, `openhtmltopdf-core` 1.1.97, `de.rototor.pdfbox:graphics2d` 3.0.1. `fontbox` arriva con PDFBox [da confermare con `mvn dependency:tree`].
- Java 25: il README dichiara test su Temurin 8, 11, 17, 21; Java 25 non citato → [DA VERIFICARE]. Modalità JVM di Quarkus: nessuna fonte → [DA VERIFICARE]; lo prova il test di accettazione.
- Determinismo: `PdfBoxRenderer.java` (ramo master) usa `info.setCreationDate(Calendar.getInstance())`; non compaiono `setDocumentID` né `COSName.ID`. PDFBox può aggiungere l'ID nel trailer da sé (PDFBOX-5053, solo da un risultato di ricerca).
- Art. 59, c. 1, lett. d, Codice del consumo (Brocardi, 16/05/2026): «la fornitura di beni che rischiano di deteriorarsi o scadere rapidamente». Art. 49 non letto → [DA VERIFICARE] su Normattiva.

## Cosa prendiamo
1. Versione **1.1.97**, da registrare in `docs/verifiche.md` con licenza e PDFBox 3.0.7. Java 25 resta [DA VERIFICARE] fino al primo `verify`.
2. Determinismo: dopo `run()`, riaprire i byte con PDFBox, fissare `creationDate`/`modificationDate` (es. 2026-10-12T00:00Z) e salvare [non confermato da fonti]. Il trailer `/ID` potrebbe restare variabile. Il test «identici byte per byte» deve generare due volte a più di 1 s di distanza; se l'ID varia, si genera una sola volta e si tiene in cache.
3. Frase del recesso: «beni che rischiano di deteriorarsi o scadere rapidamente (art. 59, comma 1, lettera d, Codice del consumo)». Non è parere legale.
4. Font standard, niente font incorporati; accenti italiani con WinAnsi [non verificato].
5. PR in revisione umana (`pom.xml`).

## Cosa evitiamo
- Dichiarare «funziona su Java 25» prima di un `verify` verde.
- Date variabili o ora corrente nel PDF.
- Citare l'art. 49 a memoria.

## Fonti (consultate il 15/10/2026; 7 letture)
- https://repo1.maven.org/maven2/io/github/openhtmltopdf/openhtmltopdf-pdfbox/maven-metadata.xml
- https://repo1.maven.org/maven2/io/github/openhtmltopdf/openhtmltopdf-pdfbox/1.1.97/openhtmltopdf-pdfbox-1.1.97.pom
- https://github.com/openhtmltopdf/openhtmltopdf
- https://github.com/danfickle/openhtmltopdf
- https://raw.githubusercontent.com/openhtmltopdf/openhtmltopdf/master/openhtmltopdf-pdfbox/src/main/java/com/openhtmltopdf/pdfboxout/PdfBoxRenderer.java
- https://www.brocardi.it/codice-del-consumo/parte-iii/titolo-iii/capo-i/sezione-ii/art59.html
- https://issues.apache.org/jira/browse/PDFBOX-5053 (solo come risultato di ricerca)
