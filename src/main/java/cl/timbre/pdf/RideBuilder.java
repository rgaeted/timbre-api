package cl.timbre.pdf;

import cl.timbre.model.DteDocument;
import cl.timbre.domain.Emisor;
import org.w3c.dom.Element;

import java.time.LocalDateTime;
import java.util.Set;

public final class RideBuilder {
    private RideBuilder() {}

    private static final Set<Integer> TIPOS_SOPORTADOS = Set.of(33, 61);

    public static byte[] build(DteDocument dte, Element ted, Emisor emisor, LocalDateTime timestamp) {
        if (!TIPOS_SOPORTADOS.contains(dte.tipoDte())) {
            throw new IllegalArgumentException("Tipo de documento no soportado para RIDE: " + dte.tipoDte());
        }
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            com.lowagie.text.Document doc = new com.lowagie.text.Document(
                com.lowagie.text.PageSize.LETTER,
                36, 36, 60, 36  // left, right, top, bottom margins
            );
            try {
                com.lowagie.text.pdf.PdfWriter.getInstance(doc, baos);
                doc.open();

                // Header: emisor on left, document type box on right
                addHeader(doc, dte, emisor);

                // Receptor details
                addReceptor(doc, dte);

                // Detail table
                addDetailTable(doc, dte);

                // References (if NC)
                if (dte.tipoDte() == 61) {
                    addReferences(doc, dte);
                }

                // Totals
                addTotals(doc, dte);

                // Footer with PDF417
                addFooter(doc, ted, emisor);
            } finally {
                if (doc.isOpen()) {
                    doc.close();
                }
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate RIDE PDF", e);
        }
    }

    private static void addHeader(com.lowagie.text.Document doc, DteDocument dte, Emisor emisor) throws com.lowagie.text.DocumentException {
        com.lowagie.text.Table headerTable = new com.lowagie.text.Table(2);
        headerTable.setWidth(100);
        headerTable.setPadding(4);

        // Left cell: emisor data
        com.lowagie.text.Cell left = new com.lowagie.text.Cell();
        left.setVerticalAlignment(com.lowagie.text.alignment.VerticalAlignment.TOP);
        left.addElement(new com.lowagie.text.Paragraph(
            new com.lowagie.text.Chunk(emisor.getRazonSocial(), PdfUtils.boldFont(11))));
        left.addElement(new com.lowagie.text.Paragraph("RUT: " + emisor.getRut(), PdfUtils.normalFont(9)));
        left.addElement(new com.lowagie.text.Paragraph("Giro: " + emisor.getGiro(), PdfUtils.normalFont(9)));
        left.addElement(new com.lowagie.text.Paragraph(emisor.getDireccionOrigen(), PdfUtils.normalFont(9)));
        headerTable.addCell(left);

        // Right cell: document type box with red border
        com.lowagie.text.Cell right = new com.lowagie.text.Cell();
        right.setVerticalAlignment(com.lowagie.text.alignment.VerticalAlignment.CENTER);
        right.setHorizontalAlignment(com.lowagie.text.alignment.HorizontalAlignment.CENTER);
        right.setBorder(com.lowagie.text.Rectangle.BOX);
        right.setBorderColor(java.awt.Color.RED);
        right.setBorderWidth(2);

        String tipoNombre;
        switch (dte.tipoDte()) {
            case 33 -> tipoNombre = "FACTURA";
            case 61 -> tipoNombre = "NOTA DE CRÉDITO";
            default -> throw new IllegalArgumentException("Tipo de documento no soportado para RIDE: " + dte.tipoDte());
        }
        right.addElement(new com.lowagie.text.Paragraph("R.U.T.: " + emisor.getRut(), PdfUtils.boldFont(9)));
        right.addElement(new com.lowagie.text.Paragraph(tipoNombre + " ELECTRÓNICA", PdfUtils.boldFont(10)));
        right.addElement(new com.lowagie.text.Paragraph("N° " + dte.folio(), PdfUtils.boldFont(12)));
        headerTable.addCell(right);

        doc.add(headerTable);
        doc.add(new com.lowagie.text.Paragraph(" "));
    }

    private static void addReceptor(com.lowagie.text.Document doc, DteDocument dte) throws com.lowagie.text.DocumentException {
        com.lowagie.text.Paragraph receptor = new com.lowagie.text.Paragraph();
        receptor.add(new com.lowagie.text.Chunk("Señor(es): ", PdfUtils.boldFont(10)));
        receptor.add(new com.lowagie.text.Chunk(dte.receptor().razonSocial(), PdfUtils.normalFont(10)));
        doc.add(receptor);

        com.lowagie.text.Paragraph rutP = new com.lowagie.text.Paragraph();
        rutP.add(new com.lowagie.text.Chunk("RUT: ", PdfUtils.boldFont(9)));
        rutP.add(new com.lowagie.text.Chunk(dte.receptor().rut(), PdfUtils.normalFont(9)));
        doc.add(rutP);

        com.lowagie.text.Paragraph giroP = new com.lowagie.text.Paragraph();
        giroP.add(new com.lowagie.text.Chunk("Giro: ", PdfUtils.boldFont(9)));
        giroP.add(new com.lowagie.text.Chunk(dte.receptor().giro(), PdfUtils.normalFont(9)));
        doc.add(giroP);

        com.lowagie.text.Paragraph dirP = new com.lowagie.text.Paragraph();
        dirP.add(new com.lowagie.text.Chunk("Dirección: ", PdfUtils.boldFont(9)));
        dirP.add(new com.lowagie.text.Chunk(dte.receptor().direccion(), PdfUtils.normalFont(9)));
        doc.add(dirP);

        com.lowagie.text.Paragraph fechaP = new com.lowagie.text.Paragraph();
        fechaP.add(new com.lowagie.text.Chunk("Fecha Emisión: ", PdfUtils.boldFont(9)));
        fechaP.add(new com.lowagie.text.Chunk(dte.fechaEmision().toString(), PdfUtils.normalFont(9)));
        doc.add(fechaP);

        doc.add(new com.lowagie.text.Paragraph(" "));
    }

    private static void addDetailTable(com.lowagie.text.Document doc, DteDocument dte) throws com.lowagie.text.DocumentException {
        com.lowagie.text.Table table = new com.lowagie.text.Table(4);
        table.setWidth(100);
        table.setAutoFillEmptyCells(true);

        // Header row
        com.lowagie.text.Cell hCant = new com.lowagie.text.Cell(new com.lowagie.text.Paragraph("Cant", PdfUtils.boldFont(9)));
        hCant.setBackgroundColor(java.awt.Color.LIGHT_GRAY);
        hCant.setHorizontalAlignment(com.lowagie.text.alignment.HorizontalAlignment.CENTER);
        table.addCell(hCant);

        com.lowagie.text.Cell hDesc = new com.lowagie.text.Cell(new com.lowagie.text.Paragraph("Descripción", PdfUtils.boldFont(9)));
        hDesc.setBackgroundColor(java.awt.Color.LIGHT_GRAY);
        table.addCell(hDesc);

        com.lowagie.text.Cell hPrecio = new com.lowagie.text.Cell(new com.lowagie.text.Paragraph("P. Unit", PdfUtils.boldFont(9)));
        hPrecio.setBackgroundColor(java.awt.Color.LIGHT_GRAY);
        hPrecio.setHorizontalAlignment(com.lowagie.text.alignment.HorizontalAlignment.RIGHT);
        table.addCell(hPrecio);

        com.lowagie.text.Cell hMonto = new com.lowagie.text.Cell(new com.lowagie.text.Paragraph("Total", PdfUtils.boldFont(9)));
        hMonto.setBackgroundColor(java.awt.Color.LIGHT_GRAY);
        hMonto.setHorizontalAlignment(com.lowagie.text.alignment.HorizontalAlignment.RIGHT);
        table.addCell(hMonto);

        // Detail rows
        java.util.List<cl.timbre.model.DteLine> lineas = dte.totales().lineas();
        if (lineas == null) {
            lineas = java.util.List.of();
        }
        for (cl.timbre.model.DteLine linea : lineas) {
            table.addCell(new com.lowagie.text.Cell(new com.lowagie.text.Paragraph(
                String.valueOf(linea.cantidad()), PdfUtils.normalFont(9))));
            table.addCell(new com.lowagie.text.Cell(new com.lowagie.text.Paragraph(
                linea.descripcion(), PdfUtils.normalFont(9))));
            com.lowagie.text.Cell punitCell = new com.lowagie.text.Cell(new com.lowagie.text.Paragraph(
                PdfUtils.formatMoney(linea.precioUnitarioNeto()), PdfUtils.normalFont(9)));
            punitCell.setHorizontalAlignment(com.lowagie.text.alignment.HorizontalAlignment.RIGHT);
            table.addCell(punitCell);
            com.lowagie.text.Cell montoCell = new com.lowagie.text.Cell(new com.lowagie.text.Paragraph(
                PdfUtils.formatMoney(linea.montoNeto()), PdfUtils.normalFont(9)));
            montoCell.setHorizontalAlignment(com.lowagie.text.alignment.HorizontalAlignment.RIGHT);
            table.addCell(montoCell);
        }

        doc.add(table);
        doc.add(new com.lowagie.text.Paragraph(" "));
    }

    private static void addReferences(com.lowagie.text.Document doc, DteDocument dte) throws com.lowagie.text.DocumentException {
        if (dte.referencias().isEmpty()) {
            return;
        }

        com.lowagie.text.Paragraph refHeader = new com.lowagie.text.Paragraph("REFERENCIAS:", PdfUtils.boldFont(10));
        doc.add(refHeader);

        for (cl.timbre.model.DteReference ref : dte.referencias()) {
            com.lowagie.text.Paragraph refLine = new com.lowagie.text.Paragraph();
            refLine.add(new com.lowagie.text.Chunk("Tipo: " + ref.tipoDocRef() + " ", PdfUtils.normalFont(8)));
            refLine.add(new com.lowagie.text.Chunk("Folio: " + ref.folioRef() + " ", PdfUtils.normalFont(8)));
            refLine.add(new com.lowagie.text.Chunk("Fecha: " + ref.fechaRef() + " ", PdfUtils.normalFont(8)));
            refLine.add(new com.lowagie.text.Chunk("Razón: " + ref.razonRef(), PdfUtils.normalFont(8)));
            doc.add(refLine);
        }

        doc.add(new com.lowagie.text.Paragraph(" "));
    }

    private static void addTotals(com.lowagie.text.Document doc, DteDocument dte) throws com.lowagie.text.DocumentException {
        com.lowagie.text.Table totalsTable = new com.lowagie.text.Table(2);
        totalsTable.setWidth(50);
        totalsTable.setHorizontalAlignment(com.lowagie.text.alignment.HorizontalAlignment.RIGHT);

        com.lowagie.text.Cell netoLabel = new com.lowagie.text.Cell(new com.lowagie.text.Paragraph("Neto:", PdfUtils.boldFont(10)));
        netoLabel.setHorizontalAlignment(com.lowagie.text.alignment.HorizontalAlignment.RIGHT);
        totalsTable.addCell(netoLabel);
        com.lowagie.text.Cell netoVal = new com.lowagie.text.Cell(new com.lowagie.text.Paragraph(
            PdfUtils.formatMoney(dte.totales().montoNeto()), PdfUtils.normalFont(10)));
        netoVal.setHorizontalAlignment(com.lowagie.text.alignment.HorizontalAlignment.RIGHT);
        totalsTable.addCell(netoVal);

        com.lowagie.text.Cell ivaLabel = new com.lowagie.text.Cell(new com.lowagie.text.Paragraph("IVA (19%):", PdfUtils.boldFont(10)));
        ivaLabel.setHorizontalAlignment(com.lowagie.text.alignment.HorizontalAlignment.RIGHT);
        totalsTable.addCell(ivaLabel);
        com.lowagie.text.Cell ivaVal = new com.lowagie.text.Cell(new com.lowagie.text.Paragraph(
            PdfUtils.formatMoney(dte.totales().iva()), PdfUtils.normalFont(10)));
        ivaVal.setHorizontalAlignment(com.lowagie.text.alignment.HorizontalAlignment.RIGHT);
        totalsTable.addCell(ivaVal);

        com.lowagie.text.Cell totalLabel = new com.lowagie.text.Cell(new com.lowagie.text.Paragraph("TOTAL:", PdfUtils.boldFont(11)));
        totalLabel.setHorizontalAlignment(com.lowagie.text.alignment.HorizontalAlignment.RIGHT);
        totalsTable.addCell(totalLabel);
        com.lowagie.text.Cell totalVal = new com.lowagie.text.Cell(new com.lowagie.text.Paragraph(
            PdfUtils.formatMoney(dte.totales().montoTotal()), PdfUtils.boldFont(11)));
        totalVal.setHorizontalAlignment(com.lowagie.text.alignment.HorizontalAlignment.RIGHT);
        totalsTable.addCell(totalVal);

        doc.add(totalsTable);
        doc.add(new com.lowagie.text.Paragraph(" "));
    }

    private static void addFooter(com.lowagie.text.Document doc, Element ted, Emisor emisor) throws com.lowagie.text.DocumentException {
        // El barcode lleva el <TED> serializado directo, en ISO-8859-1 (mismo encoding
        // que usa TedBuilder para firmar) -- asi lo pide el spec de fase C. Envolverlo en
        // Base64 antes de codificarlo -- como hacia una version anterior de este metodo --
        // infla el tamano ~33% sin necesidad (PDF417 ya soporta bytes/texto extendido de
        // forma nativa) y era, junto con el canvas fijo de abajo, lo que hacia fallar la
        // codificacion para cualquier TED real.
        String contenido = cl.timbre.xml.XmlUtil.serialize(ted);

        // PDF417Writer.encode(contents, format, width, height) primero calcula cuantas
        // columnas/filas de modulos necesita el contenido y recien despues intenta
        // encajarlas en el canvas de pixeles pedido -- si el canvas pedido no alcanza
        // ni a 1 pixel por modulo, tira "Unable to fit message in columns" (por eso una
        // version anterior de este metodo, que pedia un canvas fijo de 120x60px, fallaba
        // con cualquier TED real). Por eso se usa el encoder de bajo nivel directamente:
        // genera su propia matriz al tamano natural (1 pixel por modulo, con los limites
        // de columnas/filas por defecto de la libreria, que alcanzan de sobra para un TED)
        // y no depende de adivinar un canvas por adelantado. El tamano de impresion final
        // en el PDF lo fija scaleAbsolute(120, 60) mas abajo, independiente del tamano del
        // raster generado aca.
        int nivelCorreccionErrores = 2; // default historico de PDF417Writer.encode()
        com.google.zxing.pdf417.encoder.PDF417 encoder = new com.google.zxing.pdf417.encoder.PDF417();
        encoder.setEncoding(java.nio.charset.StandardCharsets.ISO_8859_1);
        try {
            encoder.generateBarcodeLogic(contenido, nivelCorreccionErrores);
        } catch (com.google.zxing.WriterException e) {
            throw new RuntimeException("Failed to encode PDF417 barcode", e);
        }
        byte[][] matrizEscalada = encoder.getBarcodeMatrix().getScaledMatrix(1, 1);

        // Convert scaled matrix to BufferedImage
        int alto = matrizEscalada.length;
        int ancho = matrizEscalada[0].length;
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
            ancho, alto, java.awt.image.BufferedImage.TYPE_INT_RGB
        );
        for (int y = 0; y < alto; y++) {
            for (int x = 0; x < ancho; x++) {
                image.setRGB(x, y, matrizEscalada[y][x] != 0 ? java.awt.Color.BLACK.getRGB() : java.awt.Color.WHITE.getRGB());
            }
        }

        // Embed image in PDF
        com.lowagie.text.Image barcodeImg;
        try {
            barcodeImg = com.lowagie.text.Image.getInstance(image, null);
        } catch (com.lowagie.text.BadElementException | java.io.IOException e) {
            throw new RuntimeException("Failed to embed PDF417 barcode image", e);
        }
        barcodeImg.scaleAbsolute(120, 60);

        com.lowagie.text.Paragraph footerContainer = new com.lowagie.text.Paragraph();
        footerContainer.add(barcodeImg);
        footerContainer.setAlignment(com.lowagie.text.alignment.HorizontalAlignment.CENTER.name());
        doc.add(footerContainer);

        com.lowagie.text.Paragraph timbreText = new com.lowagie.text.Paragraph("Timbre Electrónico SII", PdfUtils.normalFont(8));
        timbreText.setAlignment(com.lowagie.text.alignment.HorizontalAlignment.CENTER.name());
        doc.add(timbreText);

        com.lowagie.text.Paragraph resText = new com.lowagie.text.Paragraph(
            "Res. " + emisor.getResolucionNumero() + " de " + emisor.getResolucionFecha().getYear(),
            PdfUtils.normalFont(8)
        );
        resText.setAlignment(com.lowagie.text.alignment.HorizontalAlignment.CENTER.name());
        doc.add(resText);
    }
}
