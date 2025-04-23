package org.example;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DriftResultsProcessor {
    private static final String BASE_URL = "https://www.lasf.lt/lt/driftas/rezultatai/";
    private static final String DOWNLOAD_DIR = "downloads/";
    private static final String OUTPUT_DIR = "processed_data/";
    private static final Set<String> PROCESSED_FILES = new HashSet<>();

    public static void main(String[] args) {
        try {
            // Create directories if they don't exist
            Files.createDirectories(Paths.get(DOWNLOAD_DIR));
            Files.createDirectories(Paths.get(OUTPUT_DIR));

            // Process data for 2021-2023
            for (int year = 2021; year <= 2023; year++) {
                processYearResults(year);
            }

            System.out.println("Data processing completed successfully!");
        } catch (Exception e) {
            System.err.println("Error processing data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void processYearResults(int year) throws Exception {
        System.out.println("Processing year: " + year);

        // Connect to LASF website and get the results page
        Document doc = Jsoup.connect(BASE_URL).get();

        // Find all links containing results for the specified year
        Elements links = doc.select("a[href*=" + year + "]");

        for (Element link : links) {
            String href = link.attr("href");
            String linkText = link.text().toLowerCase();

            // Only process relevant links (results, not regulations or other pages)
            if (linkText.contains("rezultatai") || linkText.contains("results")) {
                String fileUrl = href.startsWith("http") ? href : BASE_URL + href;
                processResultFile(fileUrl, year);
            }
        }
    }

    private static void processResultFile(String fileUrl, int year) throws Exception {
        String fileName = fileUrl.substring(fileUrl.lastIndexOf('/') + 1);
        Path localPath = Paths.get(DOWNLOAD_DIR + fileName);

        // Skip already processed files
        if (PROCESSED_FILES.contains(fileName)) {
            return;
        }
        PROCESSED_FILES.add(fileName);

        System.out.println("Processing file: " + fileName);

        // Download the file
        try (InputStream in = new URL(fileUrl).openStream()) {
            Files.copy(in, localPath, StandardCopyOption.REPLACE_EXISTING);
        }

        // Process based on file type
        if (fileName.endsWith(".pdf")) {
            processPdfFile(localPath, year);
        } else if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
            processExcelFile(localPath, year);
        } else {
            System.out.println("Unsupported file format: " + fileName);
        }
    }

    private static void processPdfFile(Path filePath, int year) throws Exception {
        try (PDDocument document = PDDocument.load(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);

            // Extract competition name from file name or content
            String competitionName = extractCompetitionName(filePath.getFileName().toString(), text);

            // Process different types of PDFs (qualification, finals, etc.)
            if (text.toLowerCase().contains("kvalifikacija") || text.toLowerCase().contains("qualification")) {
                processQualificationResults(text, year, competitionName);
            } else if (text.toLowerCase().contains("finalas") || text.toLowerCase().contains("final")) {
                processFinalResults(text, year, competitionName);
            } else {
                processGeneralResults(text, year, competitionName);
            }
        }
    }

    private static void processExcelFile(Path filePath, int year) throws Exception {
        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {
            String competitionName = extractCompetitionName(filePath.getFileName().toString(), "");

            // Process each sheet in the Excel file
            for (Sheet sheet : workbook) {
                String sheetName = sheet.getSheetName().toLowerCase();

                if (sheetName.contains("kvalifikacija") || sheetName.contains("qualification")) {
                    processExcelQualification(sheet, year, competitionName);
                } else if (sheetName.contains("finalas") || sheetName.contains("final")) {
                    processExcelFinal(sheet, year, competitionName);
                } else if (sheetName.contains("rezultatai") || sheetName.contains("results")) {
                    processExcelGeneralResults(sheet, year, competitionName);
                }
            }
        }
    }

    private static String extractCompetitionName(String fileName, String content) {
        // Try to extract from file name first
        String name = fileName.replaceAll("(?i)rezultatai|results|_|" + "202[1-3]", "")
                .replaceAll("\\.(pdf|xlsx|xls)", "")
                .trim();

        if (!name.isEmpty()) {
            return name;
        }

        // Try to extract from content if name not found in file name
        Pattern pattern = Pattern.compile("Drift (serija|series|lyga|league):?\\s*(.*)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(2).trim();
        }

        return "Unknown Competition";
    }

    private static void processQualificationResults(String text, int year, String competitionName) {
        // Parse qualification results from PDF text
        System.out.println("Processing qualification results for " + competitionName + " " + year);

        String[] lines = text.split("\\r?\\n");
        List<DriverResult> results = new ArrayList<>();

        for (String line : lines) {
            if (line.matches(".*\\d+\\s+[A-Za-z]+\\s+[A-Za-z]+\\s+\\d+\\.\\d+.*")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 4) {
                    int position = Integer.parseInt(parts[0]);
                    String firstName = parts[1];
                    String lastName = parts[2];
                    double score = Double.parseDouble(parts[3]);

                    results.add(new DriverResult(firstName, lastName, position, score));
                }
            }
        }

        saveResultsToCsv(results, year, competitionName, "qualification");
    }

    private static void processFinalResults(String text, int year, String competitionName) {
        System.out.println("Processing final results for " + competitionName + " " + year);

        String[] lines = text.split("\\r?\\n");
        List<DriverResult> results = new ArrayList<>();

        // Look for patterns like "1. Vardenis Pavardenis (Team) 98.5"
        Pattern pattern = Pattern.compile("(\\d+)\\.\\s+([A-Za-z]+)\\s+([A-Za-z]+).*?(\\d+\\.?\\d*)");

        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                int position = Integer.parseInt(matcher.group(1));
                String firstName = matcher.group(2);
                String lastName = matcher.group(3);
                double score = Double.parseDouble(matcher.group(4));

                results.add(new DriverResult(firstName, lastName, position, score));
            }
        }

        saveResultsToCsv(results, year, competitionName, "final");
    }

    private static void processGeneralResults(String text, int year, String competitionName) {
        System.out.println("Processing general results for " + competitionName + " " + year);

        String[] lines = text.split("\\r?\\n");
        List<DriverResult> results = new ArrayList<>();

        // More flexible pattern for general results
        Pattern pattern = Pattern.compile("(\\d+)[\\.\\s]+([A-Za-z]+)\\s+([A-Za-z]+).*?(\\d+\\.?\\d*)");

        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                int position = Integer.parseInt(matcher.group(1));
                String firstName = matcher.group(2);
                String lastName = matcher.group(3);
                double score = matcher.group(4) != null ? Double.parseDouble(matcher.group(4)) : 0;

                results.add(new DriverResult(firstName, lastName, position, score));
            }
        }

        saveResultsToCsv(results, year, competitionName, "general");
    }

    private static void processExcelQualification(Sheet sheet, int year, String competitionName) {
        List<DriverResult> results = new ArrayList<>();

        for (Row row : sheet) {
            // Skip header rows
            if (row.getRowNum() < 2) continue;

            Cell positionCell = row.getCell(0);
            Cell nameCell = row.getCell(1);
            Cell scoreCell = row.getCell(2);

            if (positionCell != null && nameCell != null && scoreCell != null) {
                try {
                    int position = (int) positionCell.getNumericCellValue();
                    String[] nameParts = nameCell.getStringCellValue().split(" ");
                    String firstName = nameParts.length > 0 ? nameParts[0] : "";
                    String lastName = nameParts.length > 1 ? nameParts[1] : "";
                    double score = scoreCell.getNumericCellValue();

                    results.add(new DriverResult(firstName, lastName, position, score));
                } catch (Exception e) {
                    System.err.println("Error parsing row " + row.getRowNum() + ": " + e.getMessage());
                }
            }
        }

        saveResultsToCsv(results, year, competitionName, "qualification");
    }

    private static void processExcelFinal(Sheet sheet, int year, String competitionName) {
        List<DriverResult> results = new ArrayList<>();

        for (Row row : sheet) {
            // Skip header rows
            if (row.getRowNum() < 2) continue;

            Cell positionCell = row.getCell(0);
            Cell nameCell = row.getCell(1);
            Cell scoreCell = row.getCell(2);

            if (positionCell != null && nameCell != null) {
                try {
                    int position = (int) positionCell.getNumericCellValue();
                    String[] nameParts = nameCell.getStringCellValue().split(" ");
                    String firstName = nameParts.length > 0 ? nameParts[0] : "";
                    String lastName = nameParts.length > 1 ? nameParts[1] : "";
                    double score = scoreCell != null ? scoreCell.getNumericCellValue() : 0;

                    results.add(new DriverResult(firstName, lastName, position, score));
                } catch (Exception e) {
                    System.err.println("Error parsing row " + row.getRowNum() + ": " + e.getMessage());
                }
            }
        }

        saveResultsToCsv(results, year, competitionName, "final");
    }

    private static void processExcelGeneralResults(Sheet sheet, int year, String competitionName) {
        List<DriverResult> results = new ArrayList<>();

        for (Row row : sheet) {
            // Skip header rows
            if (row.getRowNum() < 1) continue;

            Cell positionCell = row.getCell(0);
            Cell nameCell = row.getCell(1);
            Cell scoreCell = row.getCell(2);

            if (positionCell != null && nameCell != null) {
                try {
                    int position = 0;
                    if (positionCell.getCellType() == CellType.NUMERIC) {
                        position = (int) positionCell.getNumericCellValue();
                    } else if (positionCell.getCellType() == CellType.STRING) {
                        position = Integer.parseInt(positionCell.getStringCellValue().replaceAll("[^0-9]", ""));
                    }

                    String[] nameParts = nameCell.getStringCellValue().split(" ");
                    String firstName = nameParts.length > 0 ? nameParts[0] : "";
                    String lastName = nameParts.length > 1 ? nameParts[1] : "";
                    double score = scoreCell != null ? scoreCell.getNumericCellValue() : 0;

                    results.add(new DriverResult(firstName, lastName, position, score));
                } catch (Exception e) {
                    System.err.println("Error parsing row " + row.getRowNum() + ": " + e.getMessage());
                }
            }
        }

        saveResultsToCsv(results, year, competitionName, "general");
    }

    private static void saveResultsToCsv(List<DriverResult> results, int year, String competitionName, String type) {
        String safeName = competitionName.replaceAll("[^a-zA-Z0-9]", "_");
        Path outputPath = Paths.get(OUTPUT_DIR + year + "_" + safeName + "_" + type + ".csv");

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
            writer.write("Position,FirstName,LastName,Score,Year,Competition,Type\n");
            for (DriverResult result : results) {
                writer.write(String.format("%d,%s,%s,%.2f,%d,%s,%s\n",
                        result.position,
                        result.firstName,
                        result.lastName,
                        result.score,
                        year,
                        competitionName,
                        type));
            }
            System.out.println("Saved results to: " + outputPath);
        } catch (IOException e) {
            System.err.println("Error writing CSV file: " + e.getMessage());
        }
    }

    // Helper class to store driver results
    private static class DriverResult {
        String firstName;
        String lastName;
        int position;
        double score;

        public DriverResult(String firstName, String lastName, int position, double score) {
            this.firstName = firstName;
            this.lastName = lastName;
            this.position = position;
            this.score = score;
        }
    }
}