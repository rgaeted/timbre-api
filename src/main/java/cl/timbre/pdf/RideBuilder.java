package cl.timbre.pdf;

import cl.timbre.model.DteDocument;
import cl.timbre.domain.Emisor;
import org.w3c.dom.Element;

import java.time.LocalDateTime;

public final class RideBuilder {
    private RideBuilder() {}

    public static byte[] build(DteDocument dte, Element ted, Emisor emisor, LocalDateTime timestamp) {
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            com.lowagie.text.Document doc = new com.lowagie.text.Document(
                com.lowagie.text.PageSize.LETTER,
                36, 36, 60, 36  // left, right, top, bottom margins
            );
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

            doc.close();
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

        String tipoNombre = dte.tipoDte() == 33 ? "FACTURA" : "NOTA DE CRÉDITO";
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
        for (cl.timbre.model.DteLine linea : dte.totales().lineas()) {
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
        // Encode TED as PDF417
        byte[] tedBytes = cl.timbre.xml.XmlUtil.serialize(ted).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

        // Truncate TED to fit within PDF417 barcode size limit (~300 bytes)
        // PDF417 has a practical size limit; we truncate the TED to the first 300 bytes
        // to ensure it can be encoded in the barcode
        if (tedBytes.length > 300) {
            byte[] truncated = new byte[300];
            System.arraycopy(tedBytes, 0, truncated, 0, 300);
            tedBytes = truncated;
        }

        com.google.zxing.BarcodeFormat format = com.google.zxing.BarcodeFormat.PDF_417;
        com.google.zxing.MultiFormatWriter writer = new com.google.zxing.MultiFormatWriter();
        com.google.zxing.common.BitMatrix matrix;
        try {
            matrix = writer.encode(
                java.util.Base64.getEncoder().encodeToString(tedBytes),
                format, 500, 250
            );
        } catch (com.google.zxing.WriterException e) {
            throw new RuntimeException("Failed to encode PDF417 barcode", e);
        }

        // Convert BitMatrix to BufferedImage
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
            matrix.getWidth(), matrix.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB
        );
        for (int x = 0; x < matrix.getWidth(); x++) {
            for (int y = 0; y < matrix.getHeight(); y++) {
                image.setRGB(x, y, matrix.get(x, y) ? java.awt.Color.BLACK.getRGB() : java.awt.Color.WHITE.getRGB());
            }
        }

        // Embed image in PDF
        com.lowagie.text.Image barcodeImg;
        try {
            barcodeImg = com.lowagie.text.Image.getInstance(image, null);
        } catch (com.lowagie.text.BadElementException | java.io.IOException e) {
            throw new RuntimeException("Failed to embed PDF417 barcode image", e);
        }
        barcodeImg.scaleAbsolute(300, 150);

        com.lowagie.text.Paragraph footerContainer = new com.lowagie.text.Paragraph();
        footerContainer.add(barcodeImg);
        footerContainer.setAlignment(com.lowagie.text.Element.ALIGN_CENTER);
        doc.add(footerContainer);

        com.lowagie.text.Paragraph timbreText = new com.lowagie.text.Paragraph("Timbre Electrónico SII", PdfUtils.normalFont(8));
        timbreText.setAlignment(com.lowagie.text.Element.ALIGN_CENTER);
        doc.add(timbreText);

        com.lowagie.text.Paragraph resText = new com.lowagie.text.Paragraph(
            "Res. " + emisor.getResolucionNumero() + " de " + emisor.getResolucionFecha().getYear(),
            PdfUtils.normalFont(8)
        );
        resText.setAlignment(com.lowagie.text.Element.ALIGN_CENTER);
        doc.add(resText);
    }
}
