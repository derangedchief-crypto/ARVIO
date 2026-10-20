<?php
require dirname(__DIR__) . '/_hub.php';
hub_headers();
readfile(dirname(__DIR__) . '/setup.html');
