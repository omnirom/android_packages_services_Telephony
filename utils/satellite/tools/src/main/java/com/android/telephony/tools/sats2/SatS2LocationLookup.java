/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.telephony.tools.sats2;

import com.android.telephony.sats2range.read.SatS2RangeFileReader;
import com.android.telephony.sats2range.read.SuffixTableRange;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.common.geometry.S2CellId;
import com.google.common.geometry.S2LatLng;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A util class for checking if a location is in the input satellite S2 file. */
public final class SatS2LocationLookup {

    private static final Pattern DMS_PATTERN =
            Pattern.compile(
                    "^\"?(\\d+)°(\\d+)'(\\d+)\"+\\s*(N|S)\\s+(\\d+)°(\\d+)'(\\d+)\"+\\s*(E|W)\"?$");

    /**
     *  A util method for checking if a location is in the input satellite S2 file.
     */
    public static void main(String[] args) throws Exception {
        Arguments arguments = new Arguments();
        JCommander.newBuilder()
                .addObject(arguments)
                .build()
                .parse(args);

        if (arguments.csvFile != null) {
            processLocationLookupFromCSV(arguments);
            return;
        }

        // Make sure either DMS or DD format location is passed
        if (arguments.dms == null && arguments.latDegrees == null && arguments.lngDegrees == null) {
            throw new IllegalArgumentException(
                    "Either --lat-degrees and --lng-degrees or --dms must be specified");
        }

        double latDegrees, lngDegrees;

        if (arguments.dms != null) {
            double[] dmsCoords = parseDMS(arguments.dms);
            latDegrees = dmsCoords[0];
            lngDegrees = dmsCoords[1];
        } else {
            latDegrees = arguments.latDegrees;
            lngDegrees = arguments.lngDegrees;
        }

        try (SatS2RangeFileReader satS2RangeFileReader =
                     SatS2RangeFileReader.open(new File(arguments.inputFile))) {
            System.out.println(
                    "lat - "
                            + latDegrees
                            + ", long - "
                            + lngDegrees
                            + ", s2Level - "
                            + satS2RangeFileReader.getS2Level());
            S2CellId s2CellId =
                    getS2CellId(latDegrees, lngDegrees, satS2RangeFileReader.getS2Level());
            System.out.println("s2CellId=" + Long.toUnsignedString(s2CellId.id())
                    + ", token=" + s2CellId.toToken());
            SuffixTableRange entry = satS2RangeFileReader.findEntryByCellId(s2CellId.id());
            if (entry == null) {
                System.out.println("The input file does not contain the input location");
            } else {
                System.out.println("The input file contains the input location, entryValue="
                        + entry.getEntryValue());
            }
        }
    }

    private static void processLocationLookupFromCSV(Arguments arguments) throws Exception {
        File inputFile = new File(arguments.inputFile);
        File csvFile = new File(arguments.csvFile);
        File outputFile = new File(arguments.outputFile);

        try (SatS2RangeFileReader satS2RangeFileReader =
                        SatS2RangeFileReader.open(new File(arguments.inputFile));
                BufferedReader csvReader = new BufferedReader(new FileReader(arguments.csvFile));
                FileWriter csvWriter = new FileWriter(arguments.outputFile)) {

            // Write header to output CSV
            csvWriter.append("Place,Distance,DMS coordinates,Satellite supported\n");

            String row = csvReader.readLine(); // skip first row
            while ((row = csvReader.readLine()) != null) {
                String[] data = row.split(",");
                if (data.length != 3) { // Handle invalid CSV rows
                    System.err.println("Skipping invalid CSV row: " + row);
                    continue;
                }

                String place = data[0].trim();
                String distance = data[1].trim();
                String dms = data[2].trim();

                // Remove the outer double quotes if present, but keep inner quotes:
                String cleanedDMS = dms.replaceAll("^\"", "").replaceAll("\"$", "").trim();

                double[] dmsCoords = parseDMS(cleanedDMS);
                double latDegrees = dmsCoords[0];
                double lngDegrees = dmsCoords[1];

                S2CellId s2CellId =
                        getS2CellId(latDegrees, lngDegrees, satS2RangeFileReader.getS2Level());
                SuffixTableRange entry = satS2RangeFileReader.findEntryByCellId(s2CellId.id());

                String supported = (entry != null) ? "Yes" : "No";

                // Write data to the output file
                csvWriter.append(String.format("%s,%s,%s,%s\n", place, distance, dms, supported));

                System.out.println(String.format("%s,%s,%s,%s\n", place, distance, dms, supported));
            }

        } catch (IOException e) {
            System.err.println("Error processing CSV file: " + e.getMessage());
            throw e;
        }

        System.out.println("Geofence lookup results are at: " + outputFile.getAbsolutePath());
    }

    private static double[] parseDMS(String dmsString) {
        Matcher matcher = DMS_PATTERN.matcher(dmsString);
        if (!matcher.matches()) {
            System.err.println("Invalid DMS format: " + dmsString);
            throw new IllegalArgumentException("Invalid DMS format: " + dmsString);
        }

        double latDegrees =
                Integer.parseInt(matcher.group(1))
                        + Integer.parseInt(matcher.group(2)) / 60.0
                        + Integer.parseInt(matcher.group(3)) / 3600.0;
        if (matcher.group(4).equals("S")) {
            latDegrees = -latDegrees;
        }

        double lngDegrees =
                Integer.parseInt(matcher.group(5))
                        + Integer.parseInt(matcher.group(6)) / 60.0
                        + Integer.parseInt(matcher.group(7)) / 3600.0;
        if (matcher.group(8).equals("W")) {
            lngDegrees = -lngDegrees;
        }

        return new double[] {latDegrees, lngDegrees};
    }

    private static S2CellId getS2CellId(double latDegrees, double lngDegrees, int s2Level) {
        // Create the leaf S2 cell containing the given S2LatLng
        S2CellId cellId = S2CellId.fromLatLng(S2LatLng.fromDegrees(latDegrees, lngDegrees));

        // Return the S2 cell at the expected S2 level
        return cellId.parent(s2Level);
    }

    private static class Arguments {
        @Parameter(names = "--input-file",
                description = "sat s2 file",
                required = true)
        public String inputFile;

        @Parameter(names = "--lat-degrees", description = "latitude in degrees")
        public Double latDegrees;

        @Parameter(names = "--lng-degrees", description = "longitude in degrees")
        public Double lngDegrees;

        @Parameter(
                names = "--dms",
                description = "coordinates in DMS format (e.g., 32°43'19\"N 117°23'40\"W)")
        public String dms;

        @Parameter(names = "--csv-file", description = "Input CSV file")
        public String csvFile;

        @Parameter(names = "--output-file", description = "Output CSV file")
        public String outputFile;
    }
}
