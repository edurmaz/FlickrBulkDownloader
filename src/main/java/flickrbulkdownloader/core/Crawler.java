
package flickrbulkdownloader.core;

import flickrbulkdownloader.extensions.ApiCallInvalidException;
import flickrbulkdownloader.extensions.Photo;
import flickrbulkdownloader.extensions.PhotoNotFoundException;
import flickrbulkdownloader.extensions.PhotoSet;
import com.flickr4java.flickr.FlickrException;
import com.flickr4java.flickr.people.User;
import flickrbulkdownloader.tools.Util;

import java.io.IOException;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


public class Crawler implements ICrawler
{
    public static boolean ENABLE_DOWNLOAD_HANDLER;// = true;
    public static boolean ENABLE_DB_INSERTS;// = true;
    public static boolean ENABLE_DB_LOOKUPS;// = true;

    public static boolean CRAWL_PICTURES;// = false;
    public static boolean CRAWL_VIDEOS;// = true;

    public static boolean CHECK_LAST_CRAWL_DATE = true;

    private FlickrApi _flickrApi;
    private IDownloadHandler _downloaderHandler;
    private IDatabaseHandler _databaseHandler;
    private Logger _logger;

    public Crawler(FlickrApi flickrApi,
                   Configuration.CrawlerConfiguration crawlerConfig,
                   IDownloadHandler downloadHandler,
                   IDatabaseHandler databaseHandler,
                   Logger logger)
            throws IOException, FlickrException
    {
        _flickrApi = flickrApi;
        _downloaderHandler = downloadHandler;
        _databaseHandler = databaseHandler;
        _logger = logger;

        ENABLE_DOWNLOAD_HANDLER = crawlerConfig.getEnableDownloadHandler();
        ENABLE_DB_INSERTS = crawlerConfig.getEnableDbInserts();
        ENABLE_DB_LOOKUPS = crawlerConfig.getEnableDbLookups();
        CRAWL_PICTURES = crawlerConfig.getCrawlPictures();
        CRAWL_VIDEOS = crawlerConfig.getCrawlVideos();
    }

    /*
        Crawler workflow:

        crawlAllPhotos()
        -> crawlPhotoSet() // all photoSets and photos that are not organized in photoSets
        -> crawlPhoto()
     */


    /*
        crawl single photo

        1. Via Call: check if specified media is allowed to be crawled (its possible to set to crawl only videos or pictures)
        2. If ENABLE_DB_LOOKUPS is true: break if photoId already exists in database
        3. If ENABLE_DOWNLOAD_HANDLER is true: download media
        4. If ENABLE_DB_INSERTS is true: insert photoId into database
     */
    private boolean crawlPhoto(Photo photo) throws IOException, SQLException, ApiCallInvalidException
    {
        if(!configAllowsCrawl(photo))
            return false;

        if(ENABLE_DB_LOOKUPS)
            if(_databaseHandler.existsPhoto(photo))
                return false;

        boolean isOriginal = true;
        if(ENABLE_DOWNLOAD_HANDLER)
        {
            int status = _downloaderHandler.downloadMedia(photo);

            if(status == -1) //download failed
                return false;

            if(status == 0)
            {
                isOriginal = false;
                _logger.log(Level.WARNING,"Original quality not available for photoId: " + photo.getId() + ". Need to download the next best quality version (Large).");
            }

            photo.setIsOriginalAvailable(isOriginal);
        }


        if(ENABLE_DB_INSERTS)
            _databaseHandler.insertPhoto(photo);

        return true;
    }

    public boolean crawlPhoto(String photoId) throws IOException, SQLException, ApiCallInvalidException
    {
        return crawlPhoto(_flickrApi.queryApiGetPhoto(photoId));
    }


    /*
        - create folder for photoset
        - crawl photoSet
     */
    private void crawlPhotoSet(PhotoSet photoSet, User user) throws IOException, SQLException, ParseException, ApiCallInvalidException
    {
        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        String dateCrawledStr = _databaseHandler.getUserDateCrawled(user);

        Date dateLastTimeCrawled;
        if(dateCrawledStr == null) //never crawled until the end
            dateLastTimeCrawled = dateFormat.parse("1900/01/01 00:00:00");
        else
            dateLastTimeCrawled = dateFormat.parse(dateCrawledStr);
        
        _logger.log(Level.INFO, "The user " + user.getUsername() + " was last time crawled at " + dateFormat.format(dateLastTimeCrawled));

        List<Photo> photoList = photoSet.getPhotoList();
        Collections.sort(photoList); //sort by dateAdded / dateUpload . oldest Photo is now the first element in list
        Collections.reverse(photoList); //newest Photo is now the first element in list

        for(Photo photo : photoList)
        {
            Date dateUpload = photo.getDateAdded();

            if(dateUpload.before(dateLastTimeCrawled) && CHECK_LAST_CRAWL_DATE)
            {
                _logger.log(Level.INFO, "Crawled until photos before " + dateFormat.format(dateLastTimeCrawled));
                break;
            }

            try
            {
                crawlPhoto(photo);
            }
            catch (PhotoNotFoundException e) //just log if photo is not found
            {
                _logger.log(Level.WARNING, "Photo with photo.getId() = " + photo.getId() + " not found!");
            }

        }

    }

    /*
        - Accepts userId and Username
        - Checks its syntax and calls the corresponding method
     */
    public void crawlAllPhotos(String userIdentification) throws IOException, SQLException, ParseException, ApiCallInvalidException
    {

        if(Util.IsUserId(userIdentification))
            crawlAllPhotosByUserId(userIdentification);
        else
            crawlAllPhotosByUsername(userIdentification);

    }

    /*
        - insert user in database if enabled
        - crawll all photosets
        - update folder names for download path
     */
    public void crawlAllPhotosByUserId(String userId) throws IOException, SQLException, ParseException, ApiCallInvalidException {
        User user = _flickrApi.queryApiGetUser(userId);

        if(!user.getRealName().equalsIgnoreCase(""))
            _downloaderHandler.setCurrentUserFolderName(user.getUsername() + "_" + user.getRealName() + "_" + user.getId()); //each user should have its own folder
        _downloaderHandler.setCurrentUserFolderName(user.getUsername()+ "_" + user.getId());

        if(ENABLE_DB_INSERTS)
            _databaseHandler.insertUserWithCheck(user);

        //Todo: need user update entry...well maybe bad idea

        List<PhotoSet> photoSetList = getAllPhotosOrganizedInPhotoSets(user.getId());

        for(PhotoSet photoSet : photoSetList)
        {
            _downloaderHandler.setCurrentPhotoSetFolderName(photoSet.getTitle().trim().replaceAll("[^a-zA-Z0-9-]", "_")); //each photoSet should have its own folder
            crawlPhotoSet(photoSet, user);
        }

        _databaseHandler.updateUserDateCrawled(user); //crawled until the end
    }

    public void crawlAllPhotosByUsername(String username) throws IOException, SQLException, ParseException, ApiCallInvalidException {
        String userId = _flickrApi.queryApiGetUserId(username);
        crawlAllPhotosByUserId(userId);
    }


    /*
        return a list of PhotoSets of specified user, where each PhotoSet contains a photoList
     */
    private List<PhotoSet> getPhotoSetListsWithPhotoLists(String userId) throws IOException, ApiCallInvalidException {
        //source
        List<PhotoSet> photoSetList = _flickrApi.queryApiGetPhotoSetList(userId); //these photoSets contain only IDs

        //destination
        List<PhotoSet> photoSetListWithPhotoLists = new ArrayList<PhotoSet>();

        for (PhotoSet photoSet : photoSetList)
        {
            List<Photo> photoList = _flickrApi.queryApiGetPhotos(photoSet); //get photoList via photoSet
            photoSet.setPhotoList(photoList);
            photoSetListWithPhotoLists.add(photoSet);
        }

        return photoSetListWithPhotoLists;
    }

    /*
        Params:
        totalPhotoList contains ALL photos
        photoSetsWithPhotoLists contains only photos in photoSets

        Result:
        Return a list of photos that are not organized in photos
        (totalPhotoList - photoSetsWithPhotoLists = photosNotInPhotoSet)
     */
    private static List<Photo> extractPhotosNotInPhotoSets(List<PhotoSet> photoSetsWithPhotoLists, List<Photo> totalPhotoList)
    {
        //second source:
        List<Photo> totalPhotoListInPhotoSets = new ArrayList<Photo>();
        for(PhotoSet photoSet : photoSetsWithPhotoLists)
        {
            List<Photo> photoList = photoSet.getPhotoList();
            totalPhotoListInPhotoSets.addAll(photoList);
        }

        //destination
        List<Photo> photosNotInPhotoSet = new ArrayList<Photo>(totalPhotoList);
        photosNotInPhotoSet.removeAll(totalPhotoListInPhotoSets);

        return photosNotInPhotoSet;
    }

    /*
        - get photoSets where each photoSet contains a photoList
        - get unsorted photos in a list and put them in a self-defined photoSet
        - merge all to one list of photoSets and return
     */
    private List<PhotoSet> getAllPhotosOrganizedInPhotoSets(String userId) throws IOException, ApiCallInvalidException {
        List<PhotoSet> photoSetList = getPhotoSetListsWithPhotoLists(userId);

        List<Photo> totalPhotoList = _flickrApi.queryApiGetPhotos(userId);
        List<Photo> photosNotInPhotoSets = extractPhotosNotInPhotoSets(photoSetList,totalPhotoList);

        //create a PhotoSet for those
        PhotoSet photoSet = new PhotoSet();
        photoSet.setId("");
        photoSet.setSecret("");
        photoSet.setTitle("Unsorted");
        photoSet.setPhotoList(photosNotInPhotoSets);

        photoSetList.add(0,photoSet); //add in first position
        return photoSetList;
    }

    /*
        determine if crawling is allowed for specified media type
        (its possible to set, that only videos or pictures should be crawled)
     */
    private static boolean configAllowsCrawl(Photo photo)
    {
        boolean isVideo = photo.getMedia().equalsIgnoreCase(FlickrApi.VIDEO);
        boolean isPicture = !isVideo;

        if(isVideo && CRAWL_VIDEOS )
        {
            return true;
        }
        else if(isPicture && CRAWL_PICTURES)
        {
            return true;
        }

        return false;
    }

    /*
        close database connection
     */
    public void close()
    {
        _databaseHandler.closeDbConnection();
    }


    public IDatabaseHandler getDatabaseHandler()
    {
        return _databaseHandler;
    }

}


