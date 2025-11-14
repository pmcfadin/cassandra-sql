#!/bin/bash

pid=`ps auxwww | grep CassandraSqlApplication | grep -v grep | awk '{print $2 }'`
kill $pid

