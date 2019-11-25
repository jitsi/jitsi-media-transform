#!/usr/bin/perl -w

# Parse the bandwidth estimation timeseries log output, and convert
# to CSV in the format expected by https://github.com/medooze/bwe-stats-viewer.
# Timeseries logging should be enabled for the relevant subclass of
#  org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator, e.g.
#  timeseries.org.jitsi.nlj.rtp.bandwidthestimation.GoogleCcEstimator.level=ALL

# Output files should be bwe-output.js.

use strict;
use Math::BigFloat;
use Scalar::Util;
use POSIX qw(strftime);

my %endpoints;

sub parse_line($)
{
    my ($line) = @_;

    my %ret;

    if ($line !~ /^{/gc) {
	return undef;
    }

    while (1) {
	if ($line !~ /\G"([A-Za-z0-9_]*)":/gc) {
	    return undef;
	}
	my $field = $1;
	if ($line =~ /\G"((?:[^\\"]|\\.)*)"/gc) {
	    $ret{$field} = $1;
	}
	elsif ($line =~ /\G([^",}]*)/gc) {
	    $ret{$field} = $1;
	}
	if ($line !~ /\G,/gc) {
	    last;
	}
    }

    if ($line !~ /\G}/gc) {
	return undef;
    }

    return \%ret;
}

sub get_field($$)
{
    my ($line, $field) = @_;
    if ($line =~ /"$field":"((?:[^\\"]|\\.)*)"[,}]/) {
	return $1;
    }
    elsif ($line =~ /"$field":([^",}]*)/) {
	return $1;
    }
    return undef;
}

sub get_ep_key($)
{
    my ($line) = @_;

    my $conf_name = $line->{conf_name};
    my $ep_id = $line->{endpoint_id};
    my $conf_time = $line->{conf_creation_time_ms};

    return undef if (!defined($conf_name) || !defined($ep_id) || !defined($conf_time));

    my $ep_key = "$conf_name:$conf_time:$ep_id";
    if (!exists($endpoints{$ep_key})) {
	$endpoints{$ep_key}{info} = [$conf_name, $conf_time, $ep_id];
    }

    return $ep_key;
}

# Determine which of two values is "smaller" modulo a modulus.
# (Assume modulus is an even number.)
sub min_modulo($$$)
{
    my ($a, $b, $modulus) = @_;
    return $a if !defined($b);
    return $b if !defined($a);

    my $delta = ($b - $a) % $modulus;
    $delta -= $modulus if ($delta > $modulus/2);

    return $delta < 0 ? $b : $a;
}

while (<>) {
    my ($line) = parse_line($_);
    next if !defined($line);

    my $key = get_ep_key($line);
    my $series = $line->{series};
    next if (!defined($key) || !defined($series));

    if ($series eq "bwe_packet_arrival" ||
	$series eq "bwe_packet_loss" ||
	$series eq "bwe_rtt" ||
	$series eq "bwe_estimate") {
	push(@{$endpoints{$key}{trace}}, $line);
    }
}

my $bzero = Math::BigFloat->new("0");
my $b1e3 = Math::BigFloat->new("1e3");

# Convert ms to another unit, without loss of precision.
sub ms_to_unit($$)
{
    my ($val, $unit) = @_;

    my $ms;
    if (Scalar::Util::blessed($val) && Scalar::Util::blessed($val) eq "Math::BigFloat")
    {
	$ms = $val;
    }
    else {
	$ms = Math::BigFloat->new($val);
    }
    return $ms->bmul($unit)->bstr();
}

sub us($)
{
    my ($val) = @_;
    return ms_to_unit($val, $b1e3);
}

# Dummy wrapper to make code clearer to read
sub ms($)
{
    my ($val) = @_;

    return $val;
}

# 0: Feedback timestamp for rtp packet
# 1: Transport wide seq num of rtp packet [Not currently used for stats viewer]
# 2: Feedback packet num [Not currently used for stats viewer]
# 3: total packet size
# 4: Sent time for packet on sender clock
# 5: Received timestamp for rtp packet pn receiver clock (or 0 if lost)
# 6: Delta time with previous sent rtp packet (0 if lost) [Not currently used for stats viewer]
# 7: Delta time with previous received timestamp (0 if lost) [Not currently used for stats viewer]
# 8: Delta sent time - delta received time
# 9: Raw Estimated bitrate [Not currently used for stats viewer]
# 10: Target bitrate for probing
# 11: Available bitrate, adjusted bwe estimation reported back to the app (BWE minus RTX allocation based on packet loss)
# 12: rtt
# 13: mark flag of RTP packet [Not currently used for stats viewer]
# 14: 1 if packet was a retransmission, 0 otherwise
# 15: 1 if packet was for probing, 0 otherwise
sub print_row($$$$$$$$$$$$$) {
    my ($out,$fb_time, $size, $sent_time, $recv_time, $sent_delta, $recv_delta, $delta_delta, $target_bitrate, $avail_bitrate, $rtt, $is_rtx, $is_probing) = @_;

    $fb_time = us($fb_time);
    $sent_time = us($sent_time);
    $recv_time = us($recv_time);
    $sent_delta = us($sent_delta);
    $recv_delta = us($recv_delta);
    $delta_delta = us($delta_delta);
    $rtt = ms($rtt);

    print $out "$fb_time|0|0|$size|$sent_time|$recv_time|$sent_delta|$recv_delta|$delta_delta|0|$target_bitrate|$avail_bitrate|$rtt|0|$is_rtx|$is_probing\n";
}

foreach my $ep (sort keys %endpoints) {
    my ($conf_name, $conf_time, $ep_id) = @{$endpoints{$ep}{info}};

    my $conf_time_str;
    if ($conf_time > 1e11) {
	$conf_time_str = strftime("%F-%T", localtime($conf_time/1e3));
    }
    else {
	$conf_time_str = $conf_time;
    }

    my $out_name = "$conf_name-$ep_id-$conf_time_str.csv";

    open(my $out, ">", "$conf_name-$ep_id-$conf_time_str.csv") or die("$out_name: $!");

    my ($last_rtt, $last_bitrate) = (0, 0);
    my ($last_sent_time, $last_recv_time);

    print STDERR "Writing $out_name\n";

    foreach my $line (@{$endpoints{$ep}{trace}}) {
	my $series = $line->{series};
	if ($series eq "bwe_rtt") {
	    $last_rtt = $line->{rtt};
	}
	elsif ($series eq "bwe_estimate") {
	    $last_bitrate = $line->{bw};
	}
	elsif ($series eq "bwe_packet_loss") {
	    print_row($out,
		      $line->{time},
		      $line->{size},
		      exists($line->{sendTime}) ? $line->{sendTime} : "0",
		      0,
		      0,
		      0,
		      0,
		      $last_bitrate,
		      $last_bitrate,
		      $last_rtt,
		      $line->{rtx} eq "true" ? 1 : 0,
		      $line->{probing} eq "true" ? 1 : 0);
	}
	elsif ($series eq "bwe_packet_arrival") {
	    my $sendTime = $bzero;
	    my $recvTime = $bzero;
	    my $sent_delta = $bzero;
	    my $recv_delta = $bzero;
	    my $delta_delta = $bzero;
	    if (exists($line->{sendTime}) && exists($line->{recvTime})) {
		$sendTime = Math::BigFloat->new($line->{sendTime});
		$recvTime = Math::BigFloat->new($line->{recvTime});
		if (defined($last_sent_time) && defined($last_recv_time)) {
		    $sent_delta = $sendTime - $last_sent_time;
		    $recv_delta = $recvTime - $last_recv_time;
		    $delta_delta = $sent_delta - $recv_delta;
		}
		$last_sent_time = $sendTime->copy();
		$last_recv_time = $recvTime->copy();
	    }
		
	    print_row($out,
		      $line->{time},
		      $line->{size},
		      $sendTime,
		      $recvTime,
		      $sent_delta,
		      $recv_delta,
		      $delta_delta,
		      $last_bitrate,
		      $last_bitrate,
		      $last_rtt,
		      $line->{rtx} eq "true" ? 1 : 0,
		      $line->{probing} eq "true" ? 1 : 0);
	}
    }

    close($out) or die("$out_name: $!");
}
