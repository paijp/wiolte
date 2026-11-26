<?php
/*
 * Receives the serial log uploaded by web/ opened as web/#t=<token>.
 * A token is valid while data/serial-tokens/<token> exists and holds a
 * future expiry time (UNIX seconds); wiolte-serial creates them.
 * The log is appended to data/serial/<token>.log.
 */
$datadir = __DIR__."/data";
$max = 16 * 1024 * 1024;

function done($code, $msg)
{
	http_response_code($code);
	header("Content-Type: text/plain");
	header("Content-Length: ".strlen($msg));
	print $msg;
	exit;
}

if ($_SERVER["REQUEST_METHOD"] !== "POST")
	done(405, "POST only\n");
$t = isset($_GET["t"]) ? $_GET["t"] : "";
if (!preg_match('/^[0-9a-f]{16,64}$/', $t))
	done(403, "bad token\n");
$tf = "$datadir/serial-tokens/$t";
if (!is_file($tf) || (int)trim(file_get_contents($tf)) < time())
	done(403, "unknown or expired token\n");
$body = file_get_contents("php://input", false, null, 0, 1024 * 1024);
$dir = "$datadir/serial";
if (!is_dir($dir) && !@mkdir($dir, 0770, true))
	done(500, "mkdir\n");
$log = "$dir/$t.log";
if (is_file($log) && filesize($log) > $max)
	done(413, "log full\n");
file_put_contents($log, $body, FILE_APPEND | LOCK_EX);
done(200, "ok\n");
