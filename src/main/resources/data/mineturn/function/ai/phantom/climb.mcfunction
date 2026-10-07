execute store result score @s mineturn.ai run mineturn ai query engaged
execute if score @s mineturn.ai matches 1 run mineturn ai retreat
execute store result score @s mineturn.ai run mineturn ai move_async 0 3 0
execute if score @s mineturn.ai matches 1 run return 1
return run mineturn ai wait
