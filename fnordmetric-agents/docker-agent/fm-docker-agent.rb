#!/usr/bin/env ruby
require "timeout"
require "socket"

###############################################################################

# Prefix every metric name with this string
METRIC_NAME_PREFIX = "/docker/"

# Report the statistics every 1 second
INTERVAL = 1.0

# Set cgroup's base path
BASE_PATH = "/sys/fs/cgroup/"

###############################################################################

if ARGV.length != 2
  puts "usage: #{$0} <host> <port>"
  puts "  Report Linux system stats to FnordMetric Server via statsd"
  puts ""
  puts "example: #{$0} 127.0.0.1 8125"
  exit 1
end

udp = UDPSocket.new
target_host = ARGV[0]
target_port = ARGV[1].to_i

loop do


docker_ps = `docker ps --no-trunc=true`

active_containers = docker_ps.read.lines[1..-1].select { |single_line| single_line.include? "Up " }
container_ids = active_containers.map { |line| /(^\w*)/.match(line).to_s }


def get_stat_path(metric, id)
  "#{BASE_PATH}#{metric.split(".", 2).first}/docker/#{id}/#{metric}"
end

# read the given stat file or return an error

def read_stat_file(path)
  unless File.exists?(path)
   raise Exception, 'file not found'
  end
   IO::read(path)
end

# collect all metrics for each container

def cycle_through_containers(container_ids)
 container_ids.each do |id|


# define files which are to be used as data source here 
  metrics_list = [
    "cpuacct.stat",
    "memory.stat",
    "blkio.io_wait_time"
  ]

  samples_array = []

  metrics_list.each do |metric|
    samples_array << get_metric_stats(get_stat_path(metric, id), id)
  end

  send_udp assemble_udp_packet samples_array
 end

end

# go through the given container's stat file and turn each line into a metric/value pair

def get_metric_stats(path, container_id)
 file = read_stat_file(path)
 samples = []
 
 file.lines.each do |line|
  key = line.split(" ", 2).first
  value = line.split(" ", 2).last
  metric = path.split("/").last

  samples << {
   :metric => metric + "/" + key,
   :value => value,
   :label => {
    :container => container_id
   }
  }
 end
 samples
end
 

def assemble_udp_packet(samples)
  packet = ""
  samples.flatten.each do |sample|
    packet << METRIC_NAME_PREFIX + sample[:metric]
    sample[:label].each do |k,v|
      packet << "[#{k}=#{v}]"
    end
    packet << ":"
    packet << sample[:value].to_s.gsub(",", ".")
    packet << "\n"
  end
  packet
end 

def send_udp(packet_data)
  begin
    udp.send packet_data, 0, target_host, target_port
  rescue Exception => e
    puts e
  end
end

cycle_through_containers(container_ids)

end
