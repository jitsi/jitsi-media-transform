#!/usr/bin/perl -w

# Parse the BandwidthEstimationTest timeseries log output, and convert
# to JSON in the format expected by chart-bwe-output.html.
# Output file should be bwe-output.js.

use strict;

print "var dataRows = [\n";
print "['Time','Bandwidth','Queue Delay'],\n";

while (<>) {
    if (/{"depth":([0-9.]*),"delay":([0-9.]*),"time":([0-9.]*),"series":"queue"}/) {
	print "[$3,null,$2],\n";
    }
    elsif (/{"time":([0-9.]*),"bw":([0-9.]*),"series":"bw"}/) {
	print "[$1,$2,null],\n";
    }
}
print "];\n";
