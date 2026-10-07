execute store result score @s mineturn.ai run mineturn ai target nearest_enemy
execute if score @s mineturn.ai matches 0 run return run mineturn ai wait
execute store result score @s mineturn.ai run mineturn ai query ready mineturn:blaze_volley
execute if score @s mineturn.ai matches 1 run return run mineturn ai use mineturn:blaze_volley
execute store result score @s mineturn.ai run mineturn ai query ready mineturn:blaze_charge
execute if score @s mineturn.ai matches 1 run return run mineturn ai use mineturn:blaze_charge
execute store result score @s mineturn.ai run mineturn ai query distance
execute if score @s mineturn.ai matches 16001.. run return run mineturn ai move_async toward_target
mineturn ai wait
