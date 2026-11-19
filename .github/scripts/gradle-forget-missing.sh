#!/usr/bin/env bash
#
# Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#      http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Run by a job that failed, from the checkout's root, before setup-gradle's post step saves the Gradle user home to
# the cache: forgets the artifacts a repository answered 404 for, so the next run of the job asks the repository again
# instead of failing from the record without a request. Maven Central answers 404 for a moment for an artifact it is
# still publishing, and Gradle records it as missing in caches/modules-2/metadata-*/module-artifact.bin.
#
# - The Gradle daemon is stopped first: a daemon that made the record keeps it in memory, and the record removed
#   under a running daemon is not forgotten. Stopping writes nothing back.
# - Only that file is removed. The downloaded files and the other records stay: removing all of metadata-* would have
#   the next run fetch every dependency again. A module whose POM answered 404 is not recorded, so it needs nothing.
# - It never fails: the job's own failure is the one to read. Without a daemon (Gradle never ran) it stops nothing,
#   and without the record it removes nothing.

set -u

home="${GRADLE_USER_HOME:-$HOME/.gradle}"

if [ -x ./gradlew ] && [ -d "$home/daemon" ]; then
  ./gradlew --stop >/dev/null 2>&1 || echo "gradle-forget-missing: ./gradlew --stop failed; the record is removed anyway"
fi

removed=0
for record in "$home"/caches/modules-2/metadata-*/module-artifact.bin; do
  [ -e "$record" ] || continue
  if rm -f "$record"; then
    echo "gradle-forget-missing: removed $record"
    removed=$((removed + 1))
  fi
done
if [ "$removed" -eq 0 ]; then
  echo "gradle-forget-missing: no record of missing artifacts in $home"
fi
exit 0
