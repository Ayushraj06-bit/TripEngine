/*
 * Clock times read the same on a laptop in Bengaluru and a CI runner in UTC. The tests run on
 * threads of one process (vmThreads), which keep that process's timezone - so it is set here, in
 * the main process, before any worker starts, rather than per test file.
 */
export default function setup(): void {
  process.env['TZ'] = 'Asia/Kolkata';
}
